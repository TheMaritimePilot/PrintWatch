/* PrintWatch dashboard. Runs inside the Android app (window.Native) or, for previewing,
   in any desktop browser with demo data. */
(function () {
  'use strict';

  var N = window.Native || null;
  var REMOTE = !!window.PW_REMOTE; // the filament library opened from a computer's browser
  var DEMO = !N && !REMOTE;
  var DEFAULTS = { printers: [], dim: true, dimLevel: 8, sound: true, clock24: true, controls: true,
    autoUpdate: true, updateToken: '', orientation: 'auto', webLibrary: false };

  var STATUS = {
    IDLE: ['Idle', 'idle'], INIT: ['Starting', 'prep'], SLICING: ['Slicing', 'prep'],
    PREPARE: ['Preparing', 'prep'], RUNNING: ['Printing', 'run'], PAUSE: ['Paused', 'pause'],
    FINISH: ['Finished', 'done'], FAILED: ['Failed', 'fail']
  };
  var ACTIVE = { PREPARE: 1, RUNNING: 1, PAUSE: 1, SLICING: 1, INIT: 1 };
  var JOB = { PREPARE: 1, RUNNING: 1, PAUSE: 1, FINISH: 1, FAILED: 1 };
  var SPEEDS = { 1: 'Silent', 2: 'Standard', 3: 'Sport', 4: 'Ludicrous' };
  var SPEED_PCT = { 1: '50%', 2: '100%', 3: '124%', 4: '166%' };
  var STAGES = {
    1: 'Auto bed leveling', 2: 'Heatbed preheating', 3: 'Sweeping XY mech mode', 4: 'Changing filament',
    5: 'M400 pause', 6: 'Paused: filament runout', 7: 'Heating hotend', 8: 'Calibrating extrusion',
    9: 'Scanning bed surface', 10: 'Inspecting first layer', 11: 'Identifying build plate',
    12: 'Calibrating micro lidar', 13: 'Homing toolhead', 14: 'Cleaning nozzle tip',
    15: 'Checking extruder temperature', 16: 'Paused by user', 17: 'Paused: front cover fell',
    18: 'Calibrating micro lidar', 19: 'Calibrating extrusion flow', 20: 'Paused: nozzle temperature fault',
    21: 'Paused: heatbed temperature fault', 22: 'Unloading filament', 23: 'Paused: skip step',
    24: 'Loading filament', 25: 'Calibrating motor noise', 26: 'Paused: AMS lost',
    27: 'Paused: heat break fan too slow', 28: 'Paused: chamber temperature fault', 29: 'Cooling chamber',
    30: 'Paused by G-code', 31: 'Motor noise showoff', 32: 'Paused: nozzle filament covered',
    33: 'Paused: cutter error', 34: 'Paused: first layer error', 35: 'Paused: nozzle clog'
  };

  var cfg;
  var cards = [];
  var lastTouch = Date.now();
  var dimmed = false;
  var swallowClick = false;
  var viewerIndex = -1;

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function num(v, d) { var n = parseFloat(v); return isNaN(n) ? (d === undefined ? 0 : d) : n; }
  function pad(n) { return (n < 10 ? '0' : '') + n; }

  // ---------------------------------------------------------------- config

  function loadConfig() {
    var c = null;
    try {
      c = REMOTE ? {} : JSON.parse(DEMO ? (localStorage.getItem('pw-config') || 'null') : N.getConfig());
    } catch (e) { c = null; }
    if (!c && DEMO) c = demoConfig();
    c = c || {};
    Object.keys(DEFAULTS).forEach(function (k) { if (c[k] === undefined) c[k] = DEFAULTS[k]; });
    return c;
  }

  function saveConfig() {
    var json = JSON.stringify(cfg);
    if (DEMO) { try { localStorage.setItem('pw-config', json); } catch (e) { /* ignore */ } }
    else N.saveConfig(json);
  }

  // ------------------------------------------------------------------ grid

  function buildGrid() {
    var grid = $('grid');
    grid.innerHTML = '';
    cards = [];
    var n = cfg.printers.length;
    grid.className = 'grid n' + Math.min(Math.max(n, 1), 4);
    document.body.classList.toggle('hide-controls', !cfg.controls);
    $('welcome').classList.toggle('hidden', n > 0);
    cfg.printers.forEach(function (p, i) { cards.push(makeCard(p, i)); });
  }

  function makeCard(p, i) {
    var node = $('cardTpl').content.firstElementChild.cloneNode(true);
    function q(s) { return node.querySelector(s); }
    var c = {
      i: i, cfg: p, node: node,
      name: q('.name'), pill: q('.pill'), wifi: q('.wifi'),
      cam: q('.cam'), live: q('img.live'), model: q('img.model'), camEmpty: q('.cam-empty'), layer: q('.cam-layer'),
      pct: q('.pct'), remain: q('.remain'), eta: q('.eta'), bar: q('.bar i'), file: q('.file'), fil: q('.fil'),
      alert: q('.alert'), nozzle: q('.nozzle'), bed: q('.bed'), fanPart: q('.fan-part'), fanAux: q('.fan-aux'),
      fanCh: q('.fan-ch'), speedLbl: q('.cbtn.speed span'), ams: q('.ams'),
      offline: q('.offline'), why: q('.offline .why'),
      btnLight: q('.cbtn.light'), btnPause: q('.cbtn.pause'), btnStop: q('.cbtn.stop'), btnSpeed: q('.cbtn.speed'),
      state: null, lastGcode: null, finishedAt: 0, attentionUntil: 0,
      frameBusy: false, frameQueued: false, frameSeq: 0, frameUrl: null, modelKey: '', amsKey: '', filKey: ''
    };
    c.name.textContent = p.name || ('Printer ' + (i + 1));
    c.camEmpty.textContent = p.camera === false ? 'Camera off' : 'Connecting camera…';
    node.addEventListener('click', function (e) { onCardClick(c, e); });
    $('grid').appendChild(node);
    render(c);
    return c;
  }

  // ---------------------------------------------------------------- render

  function fanPct(v) { return Math.round(num(v) / 15 * 10) * 10; }

  function fmtDur(min) {
    min = Math.max(0, Math.round(num(min)));
    var h = Math.floor(min / 60), m = min % 60;
    return h > 0 ? h + 'h ' + pad(m) + 'm' : m + 'm';
  }

  function fmtClock(d) {
    var h = d.getHours(), m = pad(d.getMinutes());
    if (cfg.clock24) return pad(h) + ':' + m;
    return ((h % 12) || 12) + ':' + m + (h < 12 ? ' am' : ' pm');
  }

  function readyAt(min) {
    var d = new Date(Date.now() + num(min) * 60000);
    var now = new Date();
    var day = '';
    if (d.toDateString() !== now.toDateString()) {
      var tomorrow = new Date(now.getTime() + 86400000);
      day = d.toDateString() === tomorrow.toDateString() ? 'tomorrow ' :
        d.toLocaleDateString(undefined, { weekday: 'short' }) + ' ';
    }
    return 'Ready ' + day + 'at ' + fmtClock(d);
  }

  function hex4(n) { return ('0000' + n.toString(16).toUpperCase()).slice(-4); }
  function hmsCode(h) {
    var a = num(h.attr) >>> 0, c = num(h.code) >>> 0;
    return 'HMS_' + [a >>> 16, a & 0xffff, c >>> 16, c & 0xffff].map(hex4).join('_');
  }

  function colorOf(rgba) {
    var m = /^([0-9a-f]{6})/i.exec(rgba || '');
    return m ? '#' + m[1] : null;
  }

  function temp(cur, target) {
    var frag = document.createDocumentFragment();
    frag.appendChild(document.createTextNode(Math.round(num(cur)) + '°'));
    if (num(target) > 0) frag.appendChild(el('small', null, ' / ' + Math.round(num(target)) + '°'));
    return frag;
  }

  function setNode(target, content) {
    target.textContent = '';
    target.appendChild(content);
  }

  function render(c) {
    var s = c.state || { status: 'connecting', print: {}, camera: 'connecting', model: {} };
    var p = s.print || {};
    var online = s.status === 'online';
    var hasData = !!p.gcode_state;
    var g = p.gcode_state || 'IDLE';
    var st = STATUS[g] || [g, 'idle'];
    var active = !!ACTIVE[g], job = !!JOB[g];

    // status
    c.node.className = 'card ' + (online || hasData ? st[1] : 'idle') + (online ? '' : ' is-offline');
    c.pill.textContent = online ? st[0] : (s.status === 'connecting' ? 'Connecting' : 'Offline');
    c.wifi.textContent = online && p.wifi_signal ? p.wifi_signal : '';
    var showOverlay = !online && (!hasData || s.status === 'offline');
    c.offline.classList.toggle('hidden', !showOverlay);
    if (showOverlay) {
      c.offline.querySelector('b').textContent = s.status === 'connecting' ? 'Connecting…' : 'Offline';
      c.why.textContent = s.error || '';
    }

    // progress
    var pct = Math.max(0, Math.min(100, Math.round(num(p.mc_percent))));
    var stage = STAGES[num(p.stg_cur, -1)] || '';
    c.pct.classList.remove('word');
    if (active) {
      c.pct.innerHTML = '';
      c.pct.appendChild(document.createTextNode(pct));
      c.pct.appendChild(el('small', null, '%'));
      c.remain.textContent = g === 'PREPARE' ? (stage || 'Preparing') : fmtDur(p.mc_remaining_time) + ' left';
      c.eta.textContent = g === 'PREPARE' ? '' :
        (g === 'PAUSE' ? (stage || 'Paused') : (stage && g === 'RUNNING' && num(p.stg_cur) !== 0 ? stage : readyAt(p.mc_remaining_time)));
      c.bar.style.width = pct + '%';
    } else if (g === 'FINISH') {
      c.pct.textContent = 'Done';
      c.pct.classList.add('word');
      c.remain.textContent = 'Print complete';
      c.eta.textContent = c.finishedAt ? 'Finished at ' + fmtClock(new Date(c.finishedAt)) : 'Plate ready to remove';
      c.bar.style.width = '100%';
    } else if (g === 'FAILED') {
      c.pct.textContent = 'Stopped';
      c.pct.classList.add('word');
      c.remain.textContent = 'at ' + pct + '%';
      c.eta.textContent = '';
      c.bar.style.width = pct + '%';
    } else {
      c.pct.textContent = hasData ? 'Ready' : '—';
      c.pct.classList.add('word');
      c.remain.textContent = '';
      c.eta.textContent = hasData ? 'No print running' : '';
      c.bar.style.width = '0';
    }
    c.file.textContent = job ? (p.subtask_name || '') : '';
    var layers = num(p.total_layer_num);
    c.layer.textContent = job && layers > 0 ? 'Layer ' + num(p.layer_num) + ' / ' + layers : '';

    // job preview + filament usage
    var m = s.model || {};
    var key = job && m.image ? m.key : '';
    if (key !== c.modelKey) {
      c.modelKey = key;
      if (key) c.model.src = DEMO ? demoModelImage(c.i) : '/model/' + c.i + '.png?k=' + encodeURIComponent(key);
      c.model.classList.toggle('hidden', !key);
    }
    var fil = job && m.filaments ? m.filaments.filter(function (f) { return num(f.grams) > 0; }) : [];
    var filKey = JSON.stringify(fil);
    if (filKey !== c.filKey) {
      c.filKey = filKey;
      c.fil.textContent = '';
      fil.forEach(function (f) {
        var span = el('span');
        var dot = el('i');
        dot.style.background = colorOf((f.color || '').replace('#', '')) || '#888';
        span.appendChild(dot);
        span.appendChild(document.createTextNode((f.type || 'Filament') + ' '));
        span.appendChild(el('b', null, Math.round(num(f.grams)) + ' g'));
        c.fil.appendChild(span);
      });
    }

    // alerts
    var msgs = [];
    var warnOnly = false;
    if (num(p.print_error) && g !== 'FINISH' && g !== 'IDLE') {
      var e = (num(p.print_error) >>> 0).toString(16).toUpperCase();
      e = ('00000000' + e).slice(-8);
      msgs.push(e === '0300400C' ? 'Print was cancelled' : 'Error ' + e.slice(0, 4) + '_' + e.slice(4));
    }
    (p.hms || []).forEach(function (h) { msgs.push(hmsCode(h)); });
    if (!msgs.length && g === 'PAUSE' && stage && num(p.stg_cur) !== 16) { msgs.push(stage); warnOnly = true; }
    c.alert.classList.toggle('hidden', !msgs.length || !online);
    c.alert.classList.toggle('warn', warnOnly);
    c.alert.textContent = msgs.length ? '⚠ ' + msgs.join('  ·  ') : '';

    // stats
    setNode(c.nozzle, temp(p.nozzle_temper, p.nozzle_target_temper));
    setNode(c.bed, temp(p.bed_temper, p.bed_target_temper));
    c.fanPart.textContent = fanPct(p.cooling_fan_speed) + '%';
    c.fanAux.textContent = fanPct(p.big_fan1_speed) + '%';
    c.fanCh.textContent = fanPct(p.big_fan2_speed) + '%';
    var spd = num(p.spd_lvl, 2);
    c.speedLbl.textContent = SPEEDS[spd] || '';

    // controls
    var lightOn = (p.lights_report || []).some(function (l) { return l.node === 'chamber_light' && l.mode !== 'off'; });
    c.lightOn = lightOn;
    c.btnLight.classList.toggle('on', lightOn);
    c.btnLight.disabled = !online;
    c.btnPause.classList.toggle('paused', g === 'PAUSE');
    c.btnPause.disabled = !online || !(g === 'RUNNING' || g === 'PAUSE');
    c.btnStop.disabled = !online || !(g === 'RUNNING' || g === 'PAUSE' || g === 'PREPARE');
    c.btnSpeed.disabled = !online || !(g === 'RUNNING' || g === 'PAUSE');

    // camera
    var camState = c.cfg.camera === false ? 'off' : (s.camera || 'connecting');
    if (camState !== 'online') {
      c.cam.classList.remove('live');
      c.camEmpty.textContent = camState === 'off' ? 'Camera off' :
        camState === 'offline' ? 'Camera unavailable' : 'Connecting camera…';
      if (camState !== 'off' && s.cameraError) {
        c.camEmpty.appendChild(document.createElement('br'));
        c.camEmpty.appendChild(el('small', 'cam-why', s.cameraError));
      }
    }

    renderAms(c, p, job);
  }

  function renderAms(c, p, job) {
    var ams = p.ams || {};
    var units = ams.ams || [];
    var now = String(ams.tray_now == null ? '255' : ams.tray_now);
    var F = window.PWFil;
    var items = [];
    var all = F.trays(p);
    units.forEach(function (u, ui) {
      items.push({ info: true, temp: u.temp, hum: u.humidity_raw ? u.humidity_raw + '%' : (u.humidity ? 'Level ' + u.humidity : '') });
      all.forEach(function (t) {
        if (t.unit === u) items.push(trayItem(c, t, job && now === String(t.slot)));
      });
    });
    all.forEach(function (t) {
      // the external spool holder: shown when it holds something or when there is no AMS
      if (t.slot === 254 && (t.type || !units.length || F.trayLabel(c, 254))) {
        items.push(trayItem(c, t, job && (now === '254' || (!units.length && now === '255'))));
      }
    });
    var key = JSON.stringify(items) + F.version();
    if (key === c.amsKey) return;
    c.amsKey = key;
    c.ams.textContent = '';
    items.forEach(function (it) {
      if (it.info) {
        var inf = el('div', 'amsinfo');
        if (it.temp != null && it.temp !== '') inf.appendChild(el('div', null, '\ud83c\udf21 ' + Math.round(num(it.temp)) + '\u00b0C'));
        if (it.hum) inf.appendChild(el('div', null, '\ud83d\udca7 ' + it.hum));
        if (inf.childNodes.length) c.ams.appendChild(inf);
        return;
      }
      var tray = el('button', 'tray' + (it.active ? ' active' : ''));
      tray.setAttribute('data-tray', it.slot);
      var sw = el('div', 'swatch' + (it.type ? '' : ' empty'));
      if (it.type && it.color) sw.style.background = it.color;
      tray.appendChild(sw);
      var txt = el('div');
      txt.appendChild(el('div', 't', it.type || 'Empty'));
      var sub = el('div', 's', it.name + (it.amount ? ' \u00b7 ' : ''));
      if (it.amount) sub.appendChild(el('b', it.low ? 'low' : '', it.amount));
      txt.appendChild(sub);
      tray.appendChild(txt);
      c.ams.appendChild(tray);
    });
  }

  /** One AMS / external slot for the dashboard; a linked spool's grams replace the AMS estimate. */
  function trayItem(c, t, active) {
    var lab = window.PWFil.trayLabel(c, t.slot);
    var type = t.type || (lab ? lab.material : '');
    var color = t.type ? t.color : (lab ? lab.color : null);
    return {
      slot: t.slot, name: t.slot === 254 ? 'Ext' : t.name, type: type, color: color, active: active,
      amount: lab ? lab.text : (t.type && t.remain >= 0 ? t.remain + '%' : ''), low: lab ? lab.low : false
    };
  }

  // ------------------------------------------------------- native callbacks

  function onState(i, s) {
    var c = cards[i];
    if (!c) return;
    var g = s.print && s.print.gcode_state;
    if (g && c.lastGcode && g !== c.lastGcode) {
      var was = c.lastGcode;
      if (ACTIVE[was] && (g === 'FINISH' || g === 'FAILED')) {
        if (g === 'FINISH') c.finishedAt = Date.now();
        attention(c);
      } else if (g === 'PAUSE' && was === 'RUNNING') {
        attention(c);
      }
    }
    if (g) c.lastGcode = g;
    c.state = s;
    window.PWFil.track(c, s);
    render(c);
    updateSummary();
  }

  function attention(c) {
    c.attentionUntil = Date.now() + 30 * 60000;
    if (cfg.sound && N) N.playAlert();
    updateDim();
  }

  function onFrame(i) {
    var c = cards[i];
    if (!c) return;
    if (c.frameBusy) { c.frameQueued = true; return; }
    c.frameBusy = true;
    fetch('/cam/' + i + '.jpg?n=' + (++c.frameSeq), { cache: 'no-store' })
      .then(function (r) { return r.ok ? r.blob() : null; })
      .then(function (b) { if (b) showFrame(c, URL.createObjectURL(b)); })
      .catch(function () { /* ignore */ })
      .then(function () {
        c.frameBusy = false;
        if (c.frameQueued) { c.frameQueued = false; onFrame(i); }
      });
  }

  function showFrame(c, url) {
    var old = c.frameUrl;
    c.frameUrl = url;
    c.live.src = url;
    c.cam.classList.add('live');
    if (viewerIndex === c.i) $('viewer').querySelector('img').src = url;
    if (old && old.indexOf('blob:') === 0) setTimeout(function () { URL.revokeObjectURL(old); }, 3000);
  }

  // ------------------------------------------------------------- controls

  function command(c, action, value) {
    if (DEMO) { demoCommand(c, action, value); return; }
    N.command(c.i, action, value == null ? '' : String(value));
  }

  function onCardClick(c, e) {
    var tr = e.target.closest('[data-tray]');
    if (tr) { window.PWFil.pickSpool(c, tr.getAttribute('data-tray')); return; }
    var t = e.target.closest('[data-act],[data-set],.cam');
    if (!t || !c.state || c.state.status !== 'online') {
      if (t && t.classList.contains('cam') && c.cam.classList.contains('live')) openViewer(c);
      return;
    }
    if (t.classList.contains('cam')) {
      if (c.cam.classList.contains('live')) openViewer(c);
      return;
    }
    if (!cfg.controls) return;
    var p = c.state.print || {};
    var name = c.cfg.name || 'printer';
    var act = t.getAttribute('data-act');
    var set = t.getAttribute('data-set');
    if (act === 'light') {
      c.lightOn = !c.lightOn;
      c.btnLight.classList.toggle('on', c.lightOn);
      command(c, 'light', c.lightOn ? 'on' : 'off');
    } else if (act === 'pause') {
      if (p.gcode_state === 'PAUSE') {
        command(c, 'resume');
      } else {
        confirmDialog('Pause ' + name + '?', 'The print will pause until you resume it.', 'Pause', false,
          function () { command(c, 'pause'); });
      }
    } else if (act === 'stop') {
      confirmDialog('Stop the print on ' + name + '?', 'This cancels "' + (p.subtask_name || 'the print') +
        '". It cannot be resumed.', 'Stop print', true, function () { command(c, 'stop'); });
    } else if (act === 'speed') {
      if (p.gcode_state === 'RUNNING' || p.gcode_state === 'PAUSE') speedDialog(c, num(p.spd_lvl, 2));
    } else if (set) {
      valueDialog(c, set, p);
    }
  }

  function dialog(title, body, buttons, wide) {
    var d = $('dialog');
    d.querySelector('.dlg').classList.toggle('wide', !!wide);
    d.querySelector('.dlg-title').textContent = title;
    var b = d.querySelector('.dlg-body');
    b.textContent = '';
    if (typeof body === 'string') b.textContent = body; else b.appendChild(body);
    var a = d.querySelector('.dlg-actions');
    a.textContent = '';
    buttons.forEach(function (btn) {
      var x = el('button', btn.cls || 'ghost', btn.label);
      x.addEventListener('click', function () { closeDialog(); if (btn.fn) btn.fn(); });
      a.appendChild(x);
    });
    d.classList.remove('hidden');
  }
  function closeDialog() { $('dialog').classList.add('hidden'); }
  $('dialog').addEventListener('click', function (e) { if (e.target === this) closeDialog(); });

  function confirmDialog(title, text, ok, danger, fn) {
    dialog(title, text, [{ label: 'Cancel' }, { label: ok, cls: danger ? 'danger solid' : 'primary', fn: fn }]);
  }

  function speedDialog(c, cur) {
    var box = el('div', 'speeds');
    [1, 2, 3, 4].forEach(function (lvl) {
      var b = el('button', lvl === cur ? 'sel' : '', SPEEDS[lvl]);
      b.appendChild(el('small', null, SPEED_PCT[lvl]));
      b.addEventListener('click', function () { closeDialog(); command(c, 'speed', lvl); });
      box.appendChild(b);
    });
    dialog('Print speed — ' + (c.cfg.name || ''), box, [{ label: 'Cancel' }]);
  }

  var SETTERS = {
    nozzle: { title: 'Nozzle temperature', unit: '°C', max: 300, step: 5, presets: [0, 190, 220, 250, 270], cur: 'nozzle_target_temper' },
    bed: { title: 'Bed temperature', unit: '°C', max: 110, step: 5, presets: [0, 35, 55, 65, 80, 100], cur: 'bed_target_temper' },
    fan_part: { title: 'Part cooling fan', unit: '%', max: 100, step: 10, presets: [0, 30, 50, 70, 100], fan: 'cooling_fan_speed' },
    fan_aux: { title: 'Aux fan', unit: '%', max: 100, step: 10, presets: [0, 30, 50, 70, 100], fan: 'big_fan1_speed' },
    fan_chamber: { title: 'Chamber fan', unit: '%', max: 100, step: 10, presets: [0, 30, 50, 70, 100], fan: 'big_fan2_speed' }
  };

  function valueDialog(c, key, p) {
    var m = SETTERS[key];
    var val = m.fan ? fanPct(p[m.fan]) : Math.round(num(p[m.cur]));
    var box = el('div');
    var step = el('div', 'stepper');
    var minus = el('button', null, '−'), plus = el('button', null, '+');
    var out = el('div', 'val');
    var chips = el('div', 'chips');
    function show() {
      out.textContent = val + m.unit;
      Array.prototype.forEach.call(chips.children, function (ch) { ch.classList.toggle('sel', +ch.dataset.v === val); });
    }
    minus.addEventListener('click', function () { val = Math.max(0, val - m.step); show(); });
    plus.addEventListener('click', function () { val = Math.min(m.max, val + m.step); show(); });
    m.presets.forEach(function (v) {
      var b = el('button', null, v === 0 ? 'Off' : v + m.unit);
      b.dataset.v = v;
      b.addEventListener('click', function () { val = v; show(); });
      chips.appendChild(b);
    });
    step.appendChild(minus); step.appendChild(out); step.appendChild(plus);
    box.appendChild(step); box.appendChild(chips);
    show();
    dialog(m.title + ' — ' + (c.cfg.name || ''), box,
      [{ label: 'Cancel' }, { label: 'Set', cls: 'primary', fn: function () { command(c, key, val); } }]);
  }

  function openViewer(c) {
    viewerIndex = c.i;
    var v = $('viewer');
    v.querySelector('img').src = c.live.src;
    v.querySelector('.viewer-name').textContent = c.cfg.name || '';
    v.classList.remove('hidden');
  }
  function closeViewer() { viewerIndex = -1; $('viewer').classList.add('hidden'); }
  $('viewer').addEventListener('click', closeViewer);

  // ------------------------------------------------------------- settings

  var draft;

  function openSettings() {
    draft = JSON.parse(JSON.stringify(cfg));
    if (!draft.printers.length) draft.printers.push(blankPrinter(0));
    renderForms();
    $('optDim').checked = !!draft.dim;
    $('optDimLevel').value = draft.dimLevel;
    $('optDimLevelVal').textContent = draft.dimLevel + '%';
    $('optSound').checked = !!draft.sound;
    $('opt24h').checked = !!draft.clock24;
    $('optControls').checked = !!draft.controls;
    $('optAutoUpdate').checked = draft.autoUpdate !== false;
    $('optOrientation').value = draft.orientation || 'auto';
    $('optWebLibrary').checked = !!draft.webLibrary;
    showWebAddress();
    $('optToken').value = draft.updateToken || '';
    onUpdate(N ? JSON.parse(N.updateStatus()) : { state: 'idle', message: '' });
    $('version').textContent = 'PrintWatch ' + (N ? N.appVersion() : 'preview');
    $('settings').classList.remove('hidden');
    $('settings').scrollTop = 0;
  }

  function blankPrinter(i) { return { name: 'P1S ' + (i + 1), host: '', serial: '', code: '', camera: true }; }

  function renderForms() {
    var box = $('printerForms');
    box.textContent = '';
    draft.printers.forEach(function (p, i) {
      var f = el('div', 'pform');
      var title = el('div', 'ptitle');
      title.appendChild(el('span', null, 'Printer ' + (i + 1)));
      var rm = el('button', 'danger', 'Remove');
      rm.addEventListener('click', function () { readForms(); draft.printers.splice(i, 1); renderForms(); });
      title.appendChild(rm);
      f.appendChild(title);
      [['name', 'Name', 'e.g. P1S Left'], ['host', 'IP address', 'e.g. 192.168.1.50'],
        ['serial', 'Serial number', '01P00A...'], ['code', 'Access code', '8 characters']].forEach(function (d) {
        var l = el('label', null, d[1]);
        var inp = el('input');
        inp.type = 'text';
        inp.placeholder = d[2];
        inp.value = p[d[0]] || '';
        inp.setAttribute('data-k', d[0]);
        inp.autocomplete = 'off';
        inp.spellcheck = false;
        if (d[0] === 'host') inp.inputMode = 'decimal';
        l.appendChild(inp);
        f.appendChild(l);
      });
      var cl = el('label', 'check');
      var cb = el('input');
      cb.type = 'checkbox';
      cb.checked = p.camera !== false;
      cb.setAttribute('data-k', 'camera');
      cl.appendChild(cb);
      cl.appendChild(document.createTextNode(' Show camera'));
      f.appendChild(cl);
      box.appendChild(f);
    });
  }

  function readForms() {
    var forms = $('printerForms').querySelectorAll('.pform');
    Array.prototype.forEach.call(forms, function (f, i) {
      var p = draft.printers[i];
      Array.prototype.forEach.call(f.querySelectorAll('[data-k]'), function (inp) {
        var k = inp.getAttribute('data-k');
        p[k] = inp.type === 'checkbox' ? inp.checked : inp.value.trim();
      });
      p.serial = (p.serial || '').toUpperCase();
    });
  }

  $('addPrinter').addEventListener('click', function () {
    readForms();
    draft.printers.push(blankPrinter(draft.printers.length));
    renderForms();
  });
  $('optDimLevel').addEventListener('input', function () { $('optDimLevelVal').textContent = this.value + '%'; });
  $('saveSettings').addEventListener('click', function () {
    readForms();
    draft.dim = $('optDim').checked;
    draft.dimLevel = +$('optDimLevel').value;
    draft.sound = $('optSound').checked;
    draft.clock24 = $('opt24h').checked;
    draft.controls = $('optControls').checked;
    draft.autoUpdate = $('optAutoUpdate').checked;
    draft.orientation = $('optOrientation').value;
    draft.webLibrary = $('optWebLibrary').checked;
    draft.updateToken = $('optToken').value.trim();
    cfg = draft;
    saveConfig();
    if (N) N.setOrientation(cfg.orientation || 'auto');
    setTimeout(showWebAddress, 500);
    $('settings').classList.add('hidden');
    buildGrid();
    if (DEMO) demoStart();
    updateClock();
  });
  $('cancelSettings').addEventListener('click', function () { $('settings').classList.add('hidden'); });
  $('openSettings').addEventListener('click', openSettings);
  $('welcomeSetup').addEventListener('click', openSettings);
  $('androidSettings').addEventListener('click', function () { if (N) N.openAndroidSettings(); });
  $('homeSettings').addEventListener('click', function () { if (N) N.openHomeSettings(); });

  function showWebAddress() {
    var addr = N ? N.webAddress() : '';
    $('webAddress').textContent = addr ? 'On your computer, open: ' + addr :
      ($('optWebLibrary').checked ? 'Starts when you tap Save.' : '');
  }

  // ---------------------------------------------------------------- updates

  function onUpdate(u) {
    if (!u) return;
    var msg = u.message || '';
    var line = $('updateMsg');
    line.textContent = 'Installed version ' + (N ? N.appVersion() : 'preview') + (msg ? ' \u2014 ' + msg : '');
    var needPerm = u.state === 'permission' || (N && !N.canInstall());
    $('allowInstalls').classList.toggle('hidden', !needPerm);
    $('allowReopen').classList.toggle('hidden', !N || N.canReopen());
    var note = $('updateNote');
    var show = { permission: 1, downloading: 1, installing: 1, error: 1, available: 1 }[u.state];
    note.classList.toggle('hidden', !show);
    note.classList.toggle('err', u.state === 'error' || u.state === 'permission');
    note.textContent = u.state === 'permission' ? 'Update ready \u2014 tap to allow installing' :
      u.state === 'error' ? 'Update problem \u2014 tap for details' : msg;
  }

  $('checkUpdate').addEventListener('click', function () {
    if (!N) return;
    // save a token typed just now before checking
    cfg.updateToken = $('optToken').value.trim();
    saveConfig();
    N.checkUpdate();
  });
  $('allowInstalls').addEventListener('click', function () { if (N) N.allowInstalls(); });
  $('allowReopen').addEventListener('click', function () { if (N) N.allowReopen(); });
  $('updateNote').addEventListener('click', openSettings);

  // ------------------------------------------------------ clock, dim, summary

  function updateClock() {
    var d = new Date();
    $('time').textContent = fmtClock(d);
    $('date').textContent = d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });
  }

  function updateSummary() {
    var counts = {};
    cards.forEach(function (c) {
      var s = c.state;
      var label = !s || s.status !== 'online' ? 'offline' :
        ({ RUNNING: 'printing', PREPARE: 'preparing', PAUSE: 'paused', FINISH: 'finished', FAILED: 'stopped' })[s.print && s.print.gcode_state] || 'idle';
      counts[label] = (counts[label] || 0) + 1;
    });
    $('summary').textContent = ['printing', 'preparing', 'paused', 'finished', 'stopped', 'idle', 'offline']
      .filter(function (k) { return counts[k]; })
      .map(function (k) { return counts[k] + ' ' + k; }).join('  ·  ');
  }

  function updateDim() {
    var now = Date.now();
    var busy = now - lastTouch < 120000 || !$('settings').classList.contains('hidden');
    cards.forEach(function (c) {
      var g = c.state && c.state.print && c.state.print.gcode_state;
      if (ACTIVE[g] || c.attentionUntil > now) busy = true;
    });
    var want = cfg.dim && !busy && cards.length > 0;
    if (want !== dimmed) {
      dimmed = want;
      document.body.classList.toggle('dimmed', dimmed);
      if (N) N.setBrightness(dimmed ? cfg.dimLevel / 100 : -1);
    }
  }

  // The first tap on a dimmed screen only wakes it up.
  document.addEventListener('pointerdown', function () {
    swallowClick = dimmed;
    lastTouch = Date.now();
    updateDim();
  }, true);
  document.addEventListener('click', function (e) {
    if (swallowClick) { swallowClick = false; e.stopPropagation(); e.preventDefault(); }
  }, true);

  // --------------------------------------------------------------- public

  window.PW = {
    onState: onState,
    onFrame: onFrame,
    onUpdate: onUpdate,
    onBack: function () {
      if (!$('dialog').classList.contains('hidden')) closeDialog();
      else if (viewerIndex >= 0) closeViewer();
      else if (window.PWFil.isOpen()) window.PWFil.close();
      else if (!$('settings').classList.contains('hidden')) $('settings').classList.add('hidden');
    }
  };

  // ------------------------------------------------------------------ demo

  function demoConfig() {
    return {
      printers: [
        { name: 'P1S Left', host: '192.168.1.50', serial: '01P00A000000001', code: '12345678', camera: true },
        { name: 'P1S Right', host: '192.168.1.51', serial: '01P00A000000002', code: '87654321', camera: true }
      ]
    };
  }

  var demoTimer = null;
  var demoStates = [];

  function demoState(i) {
    var trays = [
      [{ id: '0', tray_type: 'PLA', tray_color: 'F2F2F2FF', remain: 72 }, { id: '1', tray_type: 'PETG', tray_color: 'A6A9AAFF', remain: 45 },
        { id: '2', tray_type: 'PETG', tray_color: '161616FF', remain: 90 }, { id: '3', tray_type: 'PETG', tray_color: '1E4FD8FF', remain: 18 }],
      [{ id: '0', tray_type: 'PLA', tray_color: '00AE42FF', remain: 63 }, { id: '1', tray_type: 'PLA', tray_color: 'FFD000FF', remain: 100 },
        { id: '2' }, { id: '3', tray_type: 'PLA', tray_color: 'C12E1FFF', remain: 34 }]
    ][i % 2];
    var base = {
      wifi_signal: i ? '-61dBm' : '-48dBm', spd_lvl: 2, cooling_fan_speed: '15', big_fan1_speed: '0', big_fan2_speed: '6',
      lights_report: [{ node: 'chamber_light', mode: 'on' }], hms: [], print_error: 0,
      ams: { tray_now: i ? '0' : '1', ams: [{ id: '0', temp: '27.4', humidity: '4', humidity_raw: i ? '31' : '26', tray: trays }] },
      vt_tray: { id: '254', tray_type: '', tray_color: '00000000' }
    };
    if (i % 2 === 0) {
      return {
        status: 'online', camera: 'online', error: '',
        print: Object.assign(base, {
          gcode_state: 'RUNNING', mc_percent: 64, mc_remaining_time: 83, layer_num: 123, total_layer_num: 250, stg_cur: 0,
          subtask_name: 'bin-1x1x5', gcode_file: '/data/Metadata/plate_1.gcode', nozzle_temper: 244.6, nozzle_target_temper: 245,
          bed_temper: 59.8, bed_target_temper: 60
        }),
        model: { key: 'bin-1x1x5', status: 'ok', image: true, filaments: [{ type: 'PETG', color: '#A6A9AA', grams: 193.4 }] }
      };
    }
    return {
      status: 'online', camera: 'online', error: '',
      print: Object.assign(base, {
        gcode_state: 'RUNNING', mc_percent: 6, mc_remaining_time: 96, layer_num: 23, total_layer_num: 400, stg_cur: 0,
        subtask_name: 'moose-figure', gcode_file: '/data/Metadata/plate_1.gcode', nozzle_temper: 220.2, nozzle_target_temper: 220,
        bed_temper: 55.1, bed_target_temper: 55, cooling_fan_speed: '10'
      }),
      model: { key: 'moose-figure', status: 'ok', image: true, filaments: [{ type: 'PLA', color: '#00AE42', grams: 87.2 }, { type: 'PLA', color: '#FFD000', grams: 4.1 }] }
    };
  }

  function demoStart() {
    demoStates = cards.map(function (c, i) { return demoState(i); });
    cards.forEach(function (c, i) {
      onState(i, demoStates[i]);
      if (c.cfg.camera !== false) showFrame(c, demoCameraImage(i));
    });
    if (demoTimer) clearInterval(demoTimer);
    demoTimer = setInterval(function () {
      demoStates.forEach(function (s, i) {
        var p = s.print;
        if (p.gcode_state === 'RUNNING') {
          p.mc_percent = Math.min(100, p.mc_percent + 1);
          p.mc_remaining_time = Math.max(0, p.mc_remaining_time - 1);
          p.layer_num = Math.min(p.total_layer_num, p.layer_num + 2);
          if (p.mc_percent >= 100) { p.gcode_state = 'FINISH'; p.nozzle_target_temper = 0; p.bed_target_temper = 0; }
        }
        onState(i, s);
      });
    }, 5000);
  }

  function demoCommand(c, action, value) {
    var s = demoStates[c.i];
    if (!s) return;
    var p = s.print;
    if (action === 'light') p.lights_report = [{ node: 'chamber_light', mode: value }];
    else if (action === 'pause') p.gcode_state = 'PAUSE';
    else if (action === 'resume') p.gcode_state = 'RUNNING';
    else if (action === 'stop') { p.gcode_state = 'FAILED'; p.print_error = 0x0300400C; }
    else if (action === 'speed') p.spd_lvl = +value;
    else if (action === 'nozzle') p.nozzle_target_temper = +value;
    else if (action === 'bed') p.bed_target_temper = +value;
    else if (action === 'fan_part') p.cooling_fan_speed = String(Math.round(value / 100 * 15));
    else if (action === 'fan_aux') p.big_fan1_speed = String(Math.round(value / 100 * 15));
    else if (action === 'fan_chamber') p.big_fan2_speed = String(Math.round(value / 100 * 15));
    onState(c.i, s);
  }

  function demoCameraImage(i) {
    var cv = document.createElement('canvas');
    cv.width = 1280; cv.height = 720;
    var x = cv.getContext('2d');
    var g = x.createLinearGradient(0, 0, 0, 720);
    g.addColorStop(0, '#2a2d31'); g.addColorStop(1, '#0e0f11');
    x.fillStyle = g; x.fillRect(0, 0, 1280, 720);
    x.fillStyle = '#3a3532';
    x.beginPath(); x.moveTo(80, 720); x.lineTo(330, 430); x.lineTo(950, 430); x.lineTo(1200, 720); x.fill();
    x.fillStyle = i ? '#00ae42' : '#cfd2d4';
    for (var k = 0; k < 5; k++) x.fillRect(420 + k * 95, 480 - (i ? 0 : 20), 70, i ? 26 : 46);
    x.fillStyle = '#d9d9d9'; x.fillRect(560, 140, 170, 200);
    x.fillStyle = '#9a9a9a'; x.fillRect(610, 340, 70, 40);
    x.fillStyle = 'rgba(255,255,255,.5)'; x.font = '28px sans-serif';
    x.fillText('Demo camera', 30, 50);
    return cv.toDataURL('image/jpeg', 0.8);
  }

  function demoModelImage(i) {
    var col = i ? '#00ae42' : '#b9bcbf';
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">' +
      '<polygon points="100,30 170,70 100,110 30,70" fill="' + col + '" opacity=".95"/>' +
      '<polygon points="30,70 100,110 100,170 30,130" fill="' + col + '" opacity=".7"/>' +
      '<polygon points="170,70 100,110 100,170 170,130" fill="' + col + '" opacity=".5"/></svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }

  // ------------------------------------------------------------------ start

  cfg = loadConfig();
  if (REMOTE) document.body.classList.add('remote');
  window.PWFil.init({
    N: N, DEMO: DEMO, REMOTE: REMOTE, el: el, num: num, colorOf: colorOf, fmtClock: fmtClock,
    dialog: dialog, closeDialog: closeDialog, confirmDialog: confirmDialog,
    cards: function () { return cards; },
    rerender: function () { cards.forEach(function (c) { c.amsKey = ''; render(c); }); }
  });
  buildGrid();
  updateClock();
  setInterval(updateClock, 1000);
  setInterval(function () { cards.forEach(render); updateDim(); }, 30000);
  setInterval(updateDim, 5000);
  if (DEMO) demoStart();
  else if (N) N.ready();
  if (!cfg.printers.length && N) openSettings();
})();
