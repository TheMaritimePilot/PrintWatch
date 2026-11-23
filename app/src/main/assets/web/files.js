/* PrintWatch: start a print from the files on a printer's SD card (sliced .3mf files that
   Bambu Studio / OrcaSlicer sent to the printer earlier). */
(function () {
  'use strict';

  var core;
  var ACTIVE = { PREPARE: 1, RUNNING: 1, PAUSE: 1, SLICING: 1, INIT: 1 };
  var current = null; // { c, files, file, info, plate }

  function el(t, cls, txt) { return core.el(t, cls, txt); }
  function num(v, d) { return core.num(v, d); }

  function niceName(name) { return String(name || '').replace(/(\.gcode)?\.3mf$/i, ''); }
  function mb(bytes) { return bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' kB'; }
  function dur(sec) {
    var m = Math.round(num(sec) / 60), h = Math.floor(m / 60);
    return h ? h + 'h ' + ('0' + (m % 60)).slice(-2) + 'm' : m + 'm';
  }
  var MONTHS = 'JanFebMarAprMayJunJulAugSepOctNovDec';
  /** "Nov 22 10:00" or "Nov 22 2025" -> sortable number. */
  function dateKey(s) {
    var m = /^(\w{3})\s+(\d+)\s+(\d+):?(\d*)$/.exec(String(s || '').trim());
    if (!m) return 0;
    var mon = MONTHS.indexOf(m[1]) / 3, day = +m[2];
    var year = m[4] !== '' ? new Date().getFullYear() : +m[3];
    var mins = m[4] !== '' ? (+m[3]) * 60 + (+m[4]) : 0;
    var t = new Date(year, mon, day).getTime() + mins * 60000;
    if (m[4] !== '' && t > Date.now() + 86400000) t = new Date(year - 1, mon, day).getTime() + mins * 60000;
    return t;
  }

  function colorDist(a, b) {
    var x = parseInt(String(a || '#888888').slice(1, 7), 16), y = parseInt(String(b || '#888888').slice(1, 7), 16);
    if (isNaN(x) || isNaN(y)) return 200;
    var dr = (x >> 16) - (y >> 16), dg = ((x >> 8) & 255) - ((y >> 8) & 255), db = (x & 255) - (y & 255);
    return Math.sqrt(dr * dr + dg * dg + db * db);
  }

  // ------------------------------------------------------------ the list

  function open(c) {
    current = { c: c, files: null };
    showMessage('Reading the SD card of ' + (c.cfg.name || 'the printer') + '…');
    if (core.DEMO) { setTimeout(function () { onList(c.i, demoList()); }, 300); return; }
    core.N.sdList(c.i);
  }

  function showMessage(text, buttons) {
    core.dialog('Print from SD card', el('p', 'hint', text), buttons || [{ label: 'Close' }], true);
  }

  function onList(i, res) {
    if (!current || current.c.i !== i) return;
    if (res.error) {
      showMessage('Could not read the SD card: ' + res.error + '. Is there an SD card in the printer?');
      return;
    }
    var files = (res.files || []).slice().sort(function (a, b) { return dateKey(b.date) - dateKey(a.date); });
    current.files = files;
    var box = el('div', 'sdlist');
    if (!files.length) {
      box.appendChild(el('p', 'hint', 'No print files on the SD card yet. When you send a print from Bambu Studio or ' +
        'OrcaSlicer, the sliced file is stored on the printer and appears here.'));
    }
    files.forEach(function (f) {
      var row = el('button', 'sdrow');
      row.appendChild(el('span', 'sdicon', '📄'));
      var nm = el('span', 'nm');
      nm.appendChild(el('b', null, niceName(f.name)));
      nm.appendChild(el('small', null, f.date + ' · ' + mb(num(f.size)) + (f.path.indexOf('/') > 0 ? ' · ' + f.path.split('/')[0] : '')));
      row.appendChild(nm);
      row.addEventListener('click', function () { openFile(f); });
      box.appendChild(row);
    });
    core.dialog('Print from SD card — ' + (current.c.cfg.name || ''), box, [{ label: 'Close' }], true);
  }

  // ---------------------------------------------------------- one file

  function openFile(f) {
    current.file = f;
    current.info = null;
    showMessage('Reading ' + niceName(f.name) + ' from the printer… (large files can take a minute)',
      [{ label: 'Back', fn: function () { onList(current.c.i, { files: current.files }); } }]);
    if (core.DEMO) { setTimeout(function () { onInfo(current.c.i, f.path, demoInfo(f)); }, 400); return; }
    core.N.sdInfo(current.c.i, f.path, num(f.size));
  }

  function onInfo(i, path, info) {
    if (!current || current.c.i !== i || !current.file || current.file.path !== path) return;
    var back = { label: 'Back', fn: function () { onList(current.c.i, { files: current.files }); } };
    if (info.error) { showMessage('Could not read this file: ' + info.error, [back]); return; }
    var plates = info.plates || [];
    if (!plates.length) {
      showMessage('This file contains no sliced plates, so it cannot be printed from here. ' +
        'In Bambu Studio, slice it and send it to the printer (or use "Export plate sliced file").', [back]);
      return;
    }
    current.info = info;
    current.plate = plates[0].plate;
    renderFile();
  }

  function thumbUrl(name) { return core.DEMO ? name : '/sd/' + name; }

  /** The printer's slots with what is in them, for the colour mapping. */
  function slotsOf(c) {
    var p = (c.state && c.state.print) || {};
    var F = window.PWFil;
    return F.trays(p).filter(function (t) { return t.slot !== 254 || t.type || F.trayLabel(c, 254) || !(p.ams && p.ams.ams && p.ams.ams.length); })
      .map(function (t) {
        var lab = F.trayLabel(c, t.slot);
        return {
          slot: t.slot, name: t.slot === 254 ? 'External spool' : 'AMS ' + t.name,
          type: lab ? lab.material : t.type, color: lab ? lab.color : t.color,
          grams: lab ? parseFloat(lab.text) : null
        };
      });
  }

  function bestSlot(f, slots, taken) {
    var best = null, bestScore = -1e9;
    var ftype = String(f.type || '').toUpperCase();
    slots.forEach(function (s) {
      if (!s.type) return;
      var score = -colorDist(f.color, s.color);
      if (String(s.type).toUpperCase() === ftype) score += 300;
      if (taken[s.slot]) score -= 150; // prefer a different slot for each colour
      if (score > bestScore) { bestScore = score; best = s.slot; }
    });
    return best;
  }

  function renderFile() {
    var c = current.c, info = current.info, f = current.file;
    var plate = null;
    info.plates.forEach(function (p) { if (p.plate === current.plate) plate = p; });
    var box = el('div', 'sdfile');

    // plates
    if (info.plates.length > 1) {
      var tabs = el('div', 'platetabs');
      info.plates.forEach(function (p) {
        var b = el('button', 'platebtn' + (p.plate === current.plate ? ' sel' : ''));
        if (p.thumb) { var im = el('img'); im.src = thumbUrl(p.thumb); im.alt = ''; b.appendChild(im); }
        b.appendChild(el('span', null, 'Plate ' + p.plate));
        b.addEventListener('click', function () { current.plate = p.plate; renderFile(); });
        tabs.appendChild(b);
      });
      box.appendChild(tabs);
    }

    var top = el('div', 'sdtop');
    if (plate.thumb) { var img = el('img', 'sdthumb'); img.src = thumbUrl(plate.thumb); img.alt = ''; top.appendChild(img); }
    var facts = el('div', 'sdfacts');
    facts.appendChild(el('b', null, niceName(f.name) + (info.plates.length > 1 ? ' · plate ' + plate.plate : '')));
    facts.appendChild(el('span', null, '⏱ ' + dur(plate.seconds) + '   ⚖ ' + Math.round(num(plate.grams)) + ' g'));
    top.appendChild(facts);
    box.appendChild(top);

    // which slot feeds each colour
    var slots = slotsOf(c);
    var hasAms = slots.some(function (s) { return s.slot !== 254; });
    var used = (plate.filaments || []).filter(function (x) { return num(x.grams) > 0; });
    var choice = {};
    var taken = {};
    box.appendChild(el('h4', null, 'Filament'));
    var warn = el('div', 'sdwarn hidden');
    used.forEach(function (fil) {
      var row = el('div', 'maprow');
      var sw = el('i');
      sw.style.background = fil.color || '#888';
      row.appendChild(sw);
      row.appendChild(el('span', 'mapname', (fil.type || 'Filament') + ' · ' + Math.round(num(fil.grams)) + ' g'));
      var sel = el('select');
      slots.forEach(function (s) {
        var o = el('option', null, s.name + ' – ' + (s.type || 'empty') + (s.grams != null ? ' (' + Math.round(s.grams) + ' g left)' : ''));
        o.value = s.slot;
        sel.appendChild(o);
      });
      var def = hasAms ? bestSlot(fil, slots.filter(function (s) { return s.slot !== 254; }), taken) : 254;
      if (def == null && slots.length) def = slots[0].slot;
      if (def != null) { sel.value = String(def); taken[def] = true; }
      choice[fil.id] = sel;
      sel.addEventListener('change', check);
      row.appendChild(sel);
      box.appendChild(row);
    });
    if (!slots.length) box.appendChild(el('p', 'hint', 'The printer has not reported its spools yet.'));
    box.appendChild(warn);

    // options
    box.appendChild(el('h4', null, 'Options'));
    var opts = el('div', 'sdopts');
    box.appendChild(opts);
    function opt(label, on) {
      var l = el('label', 'check');
      var cb = el('input');
      cb.type = 'checkbox';
      cb.checked = on;
      l.appendChild(cb);
      l.appendChild(document.createTextNode(' ' + label));
      opts.appendChild(l);
      return cb;
    }
    var oLevel = opt('Bed levelling before printing', true);
    var oFlow = opt('Flow calibration', false);
    var oLapse = opt('Record a timelapse', false);

    var state = c.state && c.state.print && c.state.print.gcode_state;
    var busy = !c.state || c.state.status !== 'online' || ACTIVE[state];

    function check() {
      var msgs = [], blocking = false, slotsUsed = {};
      used.forEach(function (fil) {
        var s = +choice[fil.id].value;
        slotsUsed[s] = true;
        var info2 = null;
        slots.forEach(function (x) { if (x.slot === s) info2 = x; });
        if (info2 && !info2.type) { msgs.push(slotNameOf(s) + ' is empty.'); blocking = true; }
        if (info2 && info2.grams != null && info2.grams < num(fil.grams)) {
          msgs.push(slotNameOf(s) + ' may run out: the print needs ' + Math.round(num(fil.grams)) + ' g, the spool has about ' + Math.round(info2.grams) + ' g left.');
        }
        if (info2 && info2.type && String(info2.type).toUpperCase() !== String(fil.type || '').toUpperCase()) {
          msgs.push(slotNameOf(s) + ' holds ' + info2.type + ', the file was sliced for ' + fil.type + '.');
        }
      });
      if (slotsUsed[254] && Object.keys(slotsUsed).length > 1) {
        msgs.push('A print can use the AMS or the external spool, not both.'); blocking = true;
      }
      if (busy) { msgs.push('The printer is busy or offline; you can print when it is idle.'); blocking = true; }
      warn.textContent = msgs.join(' ');
      warn.classList.toggle('hidden', !msgs.length);
      warn.classList.toggle('err', blocking);
      var btn = document.querySelector('.dlg-actions .primary');
      if (btn) btn.disabled = blocking;
      return !blocking;
    }
    function slotNameOf(s) {
      var n = null;
      slots.forEach(function (x) { if (x.slot === s) n = x.name; });
      return n || ('slot ' + s);
    }

    core.dialog('Print from SD card — ' + (c.cfg.name || ''), box, [
      { label: 'Back', fn: function () { onList(c.i, { files: current.files }); } },
      {
        label: 'Print', cls: 'primary', fn: function () {
          if (!check()) return;
          var maxId = 0;
          (plate.filaments || []).forEach(function (x) { maxId = Math.max(maxId, num(x.id)); });
          var mapping = [];
          for (var k = 0; k < maxId; k++) mapping.push(-1);
          var useAms = false;
          used.forEach(function (fil) {
            var s = +choice[fil.id].value;
            if (s !== 254) { mapping[num(fil.id) - 1] = s; useAms = true; }
          });
          var req = {
            path: f.path, plate: plate.plate, useAms: useAms, mapping: mapping,
            bedLevel: oLevel.checked, flowCali: oFlow.checked, timelapse: oLapse.checked
          };
          core.confirmDialog('Start printing on ' + (c.cfg.name || 'the printer') + '?',
            niceName(f.name) + (info.plates.length > 1 ? ', plate ' + plate.plate : '') + ' – about ' + dur(plate.seconds) +
            '. Make sure the build plate is empty.', 'Print', false, function () {
              core.command(c, 'print_file', JSON.stringify(req));
              if (core.N && core.N.log) core.N.log('Start print on ' + (c.cfg.name || c.i) + ': ' + JSON.stringify(req));
            });
        }
      }
    ], true);
    check();
  }

  // ------------------------------------------------------------------ demo

  function demoList() {
    return { files: [
      { path: 'moose-figure.gcode.3mf', name: 'moose-figure.gcode.3mf', size: 2480000, date: 'Nov 22 10:04' },
      { path: 'cache/drawer-divider.3mf', name: 'drawer-divider.3mf', size: 1830000, date: 'Nov 21 19:40' },
      { path: 'cache/cable-clip.3mf', name: 'cable-clip.3mf', size: 412000, date: 'Nov 18 08:12' }
    ] };
  }
  function demoThumb(col) {
    var svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><rect width="200" height="200" fill="#e9ecef"/>' +
      '<polygon points="100,40 160,75 100,110 40,75" fill="' + col + '"/><polygon points="40,75 100,110 100,160 40,125" fill="' + col + '" opacity=".75"/>' +
      '<polygon points="160,75 100,110 100,160 160,125" fill="' + col + '" opacity=".55"/></svg>';
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  }
  function demoInfo(f) {
    if (f.name.indexOf('moose') === 0) {
      return { plates: [
        { plate: 1, thumb: demoThumb('#00ae42'), seconds: 3720, grams: 41.2, filaments: [{ id: '1', type: 'PLA', color: '#00AE42', grams: 41.2 }] },
        { plate: 2, thumb: demoThumb('#1e4fd8'), seconds: 9300, grams: 118.4, filaments: [
          { id: '1', type: 'PLA', color: '#F2F2F2', grams: 90.1 }, { id: '2', type: 'PETG', color: '#888888', grams: 15 },
          { id: '3', type: 'PETG', color: '#1E4FD8', grams: 13.3 }] }
      ] };
    }
    if (f.name.indexOf('cable') === 0) return { plates: [] };
    return { plates: [{ plate: 1, thumb: demoThumb('#a6a9aa'), seconds: 5400, grams: 142.6,
      filaments: [{ id: '1', type: 'PETG', color: '#A6A9AA', grams: 142.6 }] }] };
  }

  window.PWFiles = {
    init: function (c) { core = c; },
    open: open,
    onList: onList,
    onInfo: onInfo
  };
})();
