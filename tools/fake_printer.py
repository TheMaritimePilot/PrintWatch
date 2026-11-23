#!/usr/bin/env python3
"""A fake Bambu Lab P1S for testing PrintWatch without a real printer.

Speaks the same protocols as the printer in LAN mode, all over TLS:
  8883  MQTT (status reports, accepts commands)
  6000  camera JPEG stream
  990   implicit FTPS (job .3mf download; data connections must reuse the TLS session)

Usage: python3 fake_printer.py --cert cert.pem --key key.pem [--host 0.0.0.0] [--code 12345678]
"""
import argparse, io, json, socket, ssl, struct, threading, time, zipfile

SERIAL = "01P00A000000001"
ap = argparse.ArgumentParser()
ap.add_argument("--cert", required=True)
ap.add_argument("--key", required=True)
ap.add_argument("--host", default="127.0.0.1")
ap.add_argument("--code", default="12345678")
ap.add_argument("--speed", type=int, default=1, help="percent progress per second")
ap.add_argument("--tls13", action="store_true", help="allow TLS 1.3 (default: max TLS 1.2)")
ap.add_argument("--jpeg", default=None, help="JPEG file to stream as camera image")
args = ap.parse_args()

ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(args.cert, args.key)
if not args.tls13:
    ctx.maximum_version = ssl.TLSVersion.TLSv1_2

state = {
    "gcode_state": "RUNNING", "mc_percent": 10, "mc_remaining_time": 90, "layer_num": 20, "total_layer_num": 200,
    "subtask_name": "bin-1x1x5", "gcode_file": "/data/Metadata/plate_1.gcode", "stg_cur": 0,
    "nozzle_temper": 219.5, "nozzle_target_temper": 220, "bed_temper": 59.9, "bed_target_temper": 60,
    "cooling_fan_speed": "15", "big_fan1_speed": "0", "big_fan2_speed": "6", "spd_lvl": 2, "wifi_signal": "-50dBm",
    "hms": [], "print_error": 0, "lights_report": [{"node": "chamber_light", "mode": "on"}],
    "ams": {"tray_now": "1", "ams": [{"id": "0", "humidity": "4", "humidity_raw": "27", "temp": "26.1", "tray": [
        {"id": "0", "tray_type": "PLA", "tray_color": "FFFFFFFF", "remain": 80},
        {"id": "1", "tray_type": "PETG", "tray_color": "888888FF", "remain": 40},
        {"id": "2"}, {"id": "3", "tray_type": "PETG", "tray_color": "1E4FD8FF", "remain": 15}]}]},
}
commands = []

def log(*a): print("[fake]", *a, flush=True)

# ------------------------------------------------------------------ MQTT
def rd(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise EOFError
        b += c
    return b

def read_pkt(s):
    h = rd(s, 1)[0]
    mult, val = 1, 0
    while True:
        b = rd(s, 1)[0]
        val += (b & 127) * mult; mult *= 128
        if not b & 128: break
    return h, rd(s, val)

def enc_len(n):
    out = b""
    while True:
        b = n % 128; n //= 128
        if n: b |= 128
        out += bytes([b])
        if not n: return out

def mqtt_pub(s, lock, topic, payload):
    body = struct.pack(">H", len(topic)) + topic.encode() + payload
    with lock: s.sendall(bytes([0x30]) + enc_len(len(body)) + body)

def mqtt_client(conn):
    s = ctx.wrap_socket(conn, server_side=True)
    lock = threading.Lock()
    h, body = read_pkt(s)
    assert h >> 4 == 1
    # parse CONNECT: protocol name, level, flags, keepalive, client id, user, pass
    i = 2 + struct.unpack(">H", body[:2])[0] + 4
    def string(i):
        n = struct.unpack(">H", body[i:i+2])[0]; return body[i+2:i+2+n].decode(), i + 2 + n
    cid, i = string(i); user, i = string(i); pw, i = string(i)
    ok = user == "bblp" and pw == args.code
    log("mqtt connect", cid, "auth ok" if ok else "BAD AUTH")
    s.sendall(bytes([0x20, 2, 0, 0 if ok else 5]))
    if not ok: s.close(); return
    alive = [True]
    def reporter():
        n = 0
        while alive[0]:
            time.sleep(1); n += 1
            if state["gcode_state"] == "PREPARE":
                state["gcode_state"] = "RUNNING"
                try: mqtt_pub(s, lock, f"device/{SERIAL}/report", json.dumps({"print": {"command": "push_status", "gcode_state": "RUNNING"}}).encode())
                except Exception: alive[0] = False
                continue
            if state["gcode_state"] == "RUNNING" and state["mc_percent"] >= 100:
                state["gcode_state"] = "FINISH"
                try: mqtt_pub(s, lock, f"device/{SERIAL}/report", json.dumps({"print": {"command": "push_status", "gcode_state": "FINISH"}}).encode())
                except Exception: alive[0] = False
                continue
            if state["gcode_state"] == "RUNNING":
                state["mc_percent"] = min(100, state["mc_percent"] + args.speed)
                state["layer_num"] += 1
                delta = {"print": {"command": "push_status", "mc_percent": state["mc_percent"],
                                   "layer_num": state["layer_num"], "nozzle_temper": 219 + (n % 3) * 0.5}}
            else:
                delta = {"print": {"command": "push_status", "nozzle_temper": state["nozzle_temper"]}}
            try: mqtt_pub(s, lock, f"device/{SERIAL}/report", json.dumps(delta).encode())
            except Exception: alive[0] = False
    threading.Thread(target=reporter, daemon=True).start()
    try:
        while True:
            h, body = read_pkt(s)
            t = h >> 4
            if t == 8:  # SUBSCRIBE
                pid = body[:2]; log("mqtt subscribe", body[4:4 + struct.unpack(">H", body[2:4])[0]].decode())
                with lock: s.sendall(bytes([0x90, 3]) + pid + b"\x00")
            elif t == 12:
                with lock: s.sendall(bytes([0xD0, 0]))
                log("mqtt ping")
            elif t == 3:
                n = struct.unpack(">H", body[:2])[0]
                msg = json.loads(body[2 + n:])
                handle_command(msg, s, lock)
            elif t == 14:
                break
    except (EOFError, OSError, ssl.SSLError):
        pass
    alive[0] = False
    log("mqtt client gone")

def handle_command(msg, s, lock):
    commands.append(msg)
    log("command", json.dumps(msg))
    if "pushing" in msg:
        full = dict(state); full["command"] = "push_status"
        mqtt_pub(s, lock, f"device/{SERIAL}/report", json.dumps({"print": full}).encode())
        return
    p = msg.get("print", {}); sysm = msg.get("system", {})
    cmd = p.get("command") or sysm.get("command")
    delta = {}
    if cmd == "pause": delta["gcode_state"] = "PAUSE"
    elif cmd == "resume": delta["gcode_state"] = "RUNNING"
    elif cmd == "stop": delta.update(gcode_state="FAILED", print_error=50348044)
    elif cmd == "print_speed": delta["spd_lvl"] = int(p["param"])
    elif cmd == "ledctrl": delta["lights_report"] = [{"node": "chamber_light", "mode": sysm["led_mode"]}]
    elif cmd == "project_file":
        log("START PRINT", p["url"], p["param"], "use_ams", p["use_ams"], "mapping", p["ams_mapping"])
        delta.update(gcode_state="PREPARE", subtask_name=p["subtask_name"], gcode_file=p["url"].replace("file:///sdcard/", ""),
                     mc_percent=0, layer_num=0, print_error=0)
    elif cmd == "ams_filament_setting":
        info = {"tray_type": p["tray_type"], "tray_color": p["tray_color"], "tray_info_idx": p["tray_info_idx"],
                "nozzle_temp_min": str(p["nozzle_temp_min"]), "nozzle_temp_max": str(p["nozzle_temp_max"])}
        if p["ams_id"] == 255:
            state.setdefault("vt_tray", {"id": "254"}).update(info)
            delta["vt_tray"] = state["vt_tray"]
        else:
            unit = state["ams"]["ams"][p["ams_id"]]
            unit["tray"][p["tray_id"]].update(info)
            delta["ams"] = {"ams": [{"id": unit["id"], "tray": [dict(unit["tray"][p["tray_id"]])]}]}
    elif cmd == "gcode_line":
        g = p["param"].strip().split()
        if g[0] == "M104": delta["nozzle_target_temper"] = int(g[1][1:])
        if g[0] == "M140": delta["bed_target_temper"] = int(g[1][1:])
        if g[0] == "M106":
            key = {"P1": "cooling_fan_speed", "P2": "big_fan1_speed", "P3": "big_fan2_speed"}[g[1]]
            delta[key] = str(round(int(g[2][1:]) / 255 * 15))
    state.update(delta)
    delta["command"] = "push_status"
    mqtt_pub(s, lock, f"device/{SERIAL}/report", json.dumps({"print": delta}).encode())

# ---------------------------------------------------------------- camera
def make_jpeg():
    if args.jpeg: return open(args.jpeg, "rb").read()
    # a tiny valid JPEG (1x1 grey)
    return bytes.fromhex(
        "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00fbd3ffd9")

def camera_client(conn):
    s = ctx.wrap_socket(conn, server_side=True)
    auth = rd(s, 80)
    magic, kind = struct.unpack("<II", auth[:8])
    user = auth[16:48].rstrip(b"\0").decode(); code = auth[48:80].rstrip(b"\0").decode()
    ok = magic == 0x40 and kind == 0x3000 and user == "bblp" and code == args.code
    log("camera auth", "ok" if ok else "BAD")
    if not ok: s.close(); return
    jpeg = make_jpeg()
    try:
        while True:
            s.sendall(struct.pack("<IIII", len(jpeg), 0, 1, 0) + jpeg)
            time.sleep(0.5)
    except OSError:
        pass

# ------------------------------------------------------------------ FTPS
def make_3mf():
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr("Metadata/plate_1.png", b"\x89PNG\r\n\x1a\nFAKEPNG")
        z.writestr("Metadata/slice_info.config", """<?xml version="1.0" encoding="UTF-8"?>
<config><header><header_item key="X-BBL-Client-Type" value="slicer"/></header>
<plate><metadata key="index" value="1"/><metadata key="prediction" value="5400"/>
<filament id="2" tray_info_idx="GFG99" type="PETG" color="#888888" used_m="64.21" used_g="193.40" />
<filament id="4" tray_info_idx="GFG99" type="PETG" color="#1E4FD8" used_m="1.10" used_g="3.30" />
</plate></config>""")
    return b.getvalue()

def make_multi_3mf():
    """A sliced project with two plates; plate 2 uses three filaments."""
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        for n in (1, 2):
            z.writestr(f"Metadata/plate_{n}.gcode", f"; plate {n}\nG28\n")
            z.writestr(f"Metadata/plate_{n}.png", b"\x89PNG\r\n\x1a\nFAKEPNG" + bytes([n]))
        z.writestr("Metadata/slice_info.config", """<?xml version="1.0" encoding="UTF-8"?>
<config>
<plate><metadata key="index" value="1"/><metadata key="prediction" value="3720"/><metadata key="weight" value="41.20"/>
<filament id="1" type="PLA" color="#00AE42" used_m="13.8" used_g="41.20" /></plate>
<plate><metadata key="index" value="2"/><metadata key="prediction" value="9300"/><metadata key="weight" value="118.40"/>
<filament id="1" type="PLA" color="#00AE42" used_m="30.1" used_g="90.10" />
<filament id="2" type="PETG" color="#888888" used_m="5.0" used_g="15.00" />
<filament id="3" type="PETG" color="#1E4FD8" used_m="4.4" used_g="13.30" /></plate>
</config>""")
    return b.getvalue()

FILES = {"/cache/bin-1x1x5.3mf": make_3mf(), "/moose-figure.gcode.3mf": make_multi_3mf()}

def ftp_client(conn):
    s = ctx.wrap_socket(conn, server_side=True)
    f = s.makefile("rb")
    def say(line): s.sendall((line + "\r\n").encode())
    say("220 fake vsftpd")
    pasv = None
    authed = False
    while True:
        line = f.readline().decode().strip()
        if not line: break
        cmd, _, arg = line.partition(" ")
        cmd = cmd.upper()
        log("ftp", cmd, arg if cmd != "PASS" else "****")
        if cmd == "USER": say("331 Please specify the password.")
        elif cmd == "PASS":
            authed = arg == args.code
            say("230 Login successful." if authed else "530 Login incorrect.")
        elif not authed: say("530 Please login with USER and PASS.")
        elif cmd in ("PBSZ", "PROT", "TYPE"): say("200 OK")
        elif cmd == "SIZE":
            say(f"213 {len(FILES[arg])}" if arg in FILES else "550 Could not get file size.")
        elif cmd == "PASV":
            pasv = socket.socket(); pasv.bind((args.host, 0)); pasv.listen(1)
            port = pasv.getsockname()[1]
            h = args.host.replace(".", ",")
            say(f"227 Entering Passive Mode ({h},{port >> 8},{port & 255}).")
        elif cmd == "LIST":
            dconn, _ = pasv.accept()
            say("150 Here comes the directory listing.")
            d = ctx.wrap_socket(dconn, server_side=True)
            if not d.session_reused:
                d.close(); say("522 SSL connection failed: session reuse required"); continue
            folder = (arg or "/").rstrip("/") + "/"
            lines = []
            for path, data in FILES.items():
                head, name = path.rsplit("/", 1)
                if head + "/" == folder:
                    lines.append(f"-rw-r--r--    1 0        0        {len(data):>9} Nov 22 10:0{len(lines)} {name}")
            if folder == "/":
                lines.append("drwxr-xr-x    2 0        0             4096 Nov 20 09:00 cache")
            d.sendall(("\r\n".join(lines) + "\r\n").encode()); d.close()
            say("226 Directory send OK.")
        elif cmd in ("RETR", "NLST"):
            if cmd == "RETR" and arg not in FILES:
                say("550 Failed to open file."); continue
            dconn, _ = pasv.accept()
            say("150 Opening BINARY mode data connection.")
            d = ctx.wrap_socket(dconn, server_side=True)
            if not d.session_reused:
                log("ftp data connection did NOT reuse the TLS session -> refusing like vsftpd")
                d.close(); say("522 SSL connection failed: session reuse required"); continue
            data = FILES[arg] if cmd == "RETR" else "\r\n".join(p.split("/")[-1] for p in FILES if p.startswith(arg)).encode()
            d.sendall(data); d.unwrap() if False else None; d.close()
            say("226 Transfer complete.")
        elif cmd == "QUIT": say("221 Goodbye."); break
        else: say("502 Command not implemented.")
    s.close()

def serve(port, handler):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, port)); srv.listen(8)
    def loop():
        while True:
            c, _ = srv.accept()
            threading.Thread(target=lambda: _safe(handler, c), daemon=True).start()
    threading.Thread(target=loop, daemon=True).start()

def _safe(h, c):
    try: h(c)
    except Exception as e: log("handler error:", type(e).__name__, e)

serve(8883, mqtt_client); serve(6000, camera_client); serve(990, ftp_client)
log(f"fake printer on {args.host} serial {SERIAL} access code {args.code}")
while True: time.sleep(3600)
