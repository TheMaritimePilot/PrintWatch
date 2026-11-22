/* PrintWatch filament library: spools, AMS slot assignment and automatic usage tracking.
   Like Spoolman, but built in and stored on the tablet. */
(function () {
  'use strict';

  var core;          // helpers handed over by app.js
  var data;          // { spools, slots, jobs, done, history, since, currency, low }
  var ver = 0;       // bumps on every change so the dashboard re-renders tray labels
  var saveTimer = null;
  var tab = 'spools';

  var ACTIVE = { PREPARE: 1, RUNNING: 1, PAUSE: 1 };
  var NO_RFID = /^0*$/;
  var MATERIALS = ['PLA', 'PLA Matte', 'PLA Silk', 'PLA-CF', 'PETG', 'PETG-CF', 'ABS', 'ASA', 'TPU', 'PC', 'PA', 'PA-CF', 'PVA', 'Support'];

  function $(id) { return document.getElementById(id); }
  function el(t, c, x) { return core.el(t, c, x); }
  function num(v, d) { return core.num(v, d); }
  function round1(n) { return Math.round(n * 10) / 10; }
  function uid() { return 's' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }

  // ------------------------------------------------------------- storage

  function load() {
    if (core.REMOTE) { data = {}; load2(); return; }
    var raw = null;
    try { raw = core.DEMO ? localStorage.getItem('pw-filament') : core.N.loadData('filament'); } catch (e) { raw = null; }
    try { data = raw ? JSON.parse(raw) : null; } catch (e) { data = null; }
    if (!data && core.DEMO) data = demoData();
    data = data || {};
    ['spools', 'history', 'done'].forEach(function (k) { if (!Array.isArray(data[k])) data[k] = []; });
    ['slots', 'jobs'].forEach(function (k) { if (!data[k] || typeof data[k] !== 'object') data[k] = {}; });
    if (!data.since) data.since = Date.now();
    if (data.low == null) data.low = 150;
    if (data.currency == null) data.currency = '';
  }


  function save() {
    ver++;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      var json = JSON.stringify(data);
      if (core.DEMO) { try { localStorage.setItem('pw-filament', json); } catch (e) { /* ignore */ } }
      else core.N.saveData('filament', json);
    }, 250);
  }

  // --------------------------------------------------------------- spools

  function spool(id) {
    for (var i = 0; i < data.spools.length; i++) if (data.spools[i].id === id) return data.spools[i];
    return null;
  }
  function remaining(sp) { return Math.max(0, num(sp.initial) - num(sp.used)); }
  function spoolTitle(sp) { return [sp.brand, sp.sub || sp.material].filter(Boolean).join(' ') || 'Spool'; }
  function spoolName(sp) { return spoolTitle(sp) + (sp.colorName ? ' · ' + sp.colorName : ''); }
  function money(v) { return (data.currency || '') + (Math.round(v * 100) / 100).toFixed(2); }

  function printerKey(c) { return c.cfg.serial || ('#' + c.i); }
  function slotKey(c, slot) { return printerKey(c) + ':' + slot; }
  function slotLabel(slot) {
    slot = num(slot, -1);
    if (slot === 254) return 'Ext';
    if (slot < 0 || slot > 15) return '?';
    return String.fromCharCode(65 + Math.floor(slot / 4)) + (slot % 4 + 1);
  }

  /** Where a spool is loaded, e.g. "P1S Left · A2", or ''. */
  function whereLoaded(id) {
    var keys = Object.keys(data.slots);
    for (var i = 0; i < keys.length; i++) {
      if (data.slots[keys[i]] !== id) continue;
      var parts = keys[i].split(':');
      var printer = parts[0];
      var name = printer;
      printerList().forEach(function (pr) { if (pr.key === printer) name = pr.name || name; });
      return name + ' \u2013 ' + slotLongName(parts[1]);
    }
    return '';
  }
  function whereIs(id) {
    var sp = spool(id);
    return whereLoaded(id) || (sp && sp.place) || 'Shelf';
  }

  function assign(c, slot, id) {
    // a physical spool can only be in one place
    Object.keys(data.slots).forEach(function (k) { if (data.slots[k] === id) delete data.slots[k]; });
    if (id) data.slots[slotKey(c, slot)] = id; else delete data.slots[slotKey(c, slot)];
    save();
    core.rerender();
  }

  // ------------------------------------------------------------ AMS trays

  /** All trays of a printer's state: AMS units plus the external spool holder. */
  function trays(p) {
    var ams = (p && p.ams) || {};
    var out = [];
    (ams.ams || []).forEach(function (u, ui) {
      var unit = num(u.id, ui);
      (u.tray || []).forEach(function (t) {
        out.push(trayInfo(unit * 4 + num(t.id), t, u));
      });
    });
    if (p && p.vt_tray) out.push(trayInfo(254, p.vt_tray, null));
    return out;
  }

  function trayInfo(slot, t, unit) {
    var uuid = t.tray_uuid || '';
    return {
      slot: slot, name: slotLabel(slot), unit: unit,
      type: t.tray_type || '', sub: t.tray_sub_brands || '',
      color: core.colorOf(t.tray_color), remain: num(t.remain, -1),
      weight: num(t.tray_weight, 0), rfid: uuid && !NO_RFID.test(uuid) ? uuid : ''
    };
  }

  /** Text for a tray on the dashboard when a spool is assigned. */
  function trayLabel(c, slot) {
    var sp = spool(data.slots[slotKey(c, slot)]);
    if (!sp) return null;
    var r = remaining(sp);
    return { text: Math.round(r) + ' g', low: r < data.low, material: sp.material, color: sp.colorHex };
  }

  // --------------------------------------------------------------- tracking

  function jobKey(p) {
    var start = num(p.gcode_start_time);
    return (p.subtask_name || '') + '@' + (start > 0 ? start : (p.task_id || '') + '/' + (p.total_layer_num || ''));
  }

  /** Called with every printer update. Links RFID spools and books filament usage after a print. */
  function track(c, s) {
    if (!s || s.status !== 'online' || !s.print) return;
    var p = s.print;
    var changed = false;

    // Bambu spools with an RFID chip: recognise (or add) them automatically
    trays(p).forEach(function (t) {
      var key = slotKey(c, t.slot);
      if (t.rfid) {
        var sp = null;
        data.spools.forEach(function (x) { if (x.rfid === t.rfid) sp = x; });
        if (!sp) {
          var initial = t.weight > 0 ? t.weight : 1000;
          sp = {
            id: uid(), brand: 'Bambu Lab', material: t.type, sub: t.sub, colorHex: t.color || '#888888', colorName: '',
            initial: initial, used: t.remain >= 0 ? round1(initial * (1 - t.remain / 100)) : 0, tare: 250,
            price: 0, rfid: t.rfid, note: 'Added automatically from the AMS', created: Date.now()
          };
          data.spools.push(sp);
          changed = true;
        }
        if (data.slots[key] !== sp.id) {
          Object.keys(data.slots).forEach(function (k) { if (data.slots[k] === sp.id) delete data.slots[k]; });
          data.slots[key] = sp.id;
          changed = true;
        }
      } else if (!t.type && data.slots[key] && t.slot !== 254) {
        delete data.slots[key]; // spool taken out of the AMS
        changed = true;
      }
    });

    var g = p.gcode_state;
    var name = p.subtask_name || '';
    var pk = printerKey(c);
    var key = jobKey(p);
    if (ACTIVE[g] && name) {
      var j = data.jobs[pk];
      if (!j || j.key !== key) {
        j = data.jobs[pk] = { key: key, name: name, trays: {}, pct: 0, started: Date.now() };
        changed = true;
      }
      var now = String(p.ams && p.ams.tray_now != null ? p.ams.tray_now : '255');
      if (g === 'RUNNING' && now !== '255' && !j.trays[now]) {
        var info = null;
        trays(p).forEach(function (t) { if (String(t.slot) === now) info = t; });
        j.trays[now] = info ? { type: info.type, color: info.color } : {};
        changed = true;
      }
      if (num(p.mc_percent) > j.pct) { j.pct = num(p.mc_percent); changed = true; }
    } else if ((g === 'FINISH' || g === 'FAILED') && name && data.done.indexOf(key) < 0) {
      var job = data.jobs[pk];
      var start = num(p.gcode_start_time) * 1000;
      var seen = job && job.key === key;
      if (seen || start > data.since) {
        var m = s.model || {};
        if (m.status === 'ok' && String(m.key || '').indexOf(name + '|') === 0) {
          book(c, p, key, job, m.filaments || [], g);
          return;
        }
        if (String(m.status || '').indexOf('error') === 0) {
          c.filErrorSince = c.filErrorSince || Date.now();
          if (Date.now() - c.filErrorSince > 8 * 60000) { // the app retries the download for a few minutes
            book(c, p, key, job, null, g);
            return;
          }
        }
      }
    }
    if (changed) save();
  }

  function colorDist(a, b) {
    if (!a || !b) return 200;
    var x = parseInt(a.slice(1), 16), y = parseInt(b.slice(1), 16);
    var dr = (x >> 16) - (y >> 16), dg = ((x >> 8) & 255) - ((y >> 8) & 255), db = (x & 255) - (y & 255);
    return Math.sqrt(dr * dr + dg * dg + db * db);
  }

  /** Which AMS slot fed a filament of the print file: the slots used during the print, best match on type + colour. */
  function matchSlot(p, seen, f, single) {
    var cands = [];
    Object.keys(seen || {}).forEach(function (k) { cands.push({ slot: num(k), type: seen[k].type, color: seen[k].color }); });
    if (single && cands.length === 1) return cands[0].slot;
    if (!cands.length) {
      var all = trays(p);
      // no AMS: everything comes from the external spool holder
      if (!all.some(function (t) { return t.slot !== 254; })) return 254;
      cands = all.filter(function (t) { return t.type; });
    }
    var best = null, bestScore = -1e9;
    var ftype = String(f.type || '').toUpperCase();
    var fcol = core.colorOf(String(f.color || '').replace('#', ''));
    cands.forEach(function (t) {
      var score = -colorDist(fcol, t.color);
      if (String(t.type || '').toUpperCase() === ftype) score += 300;
      if (score > bestScore) { bestScore = score; best = t.slot; }
    });
    return best;
  }

  function book(c, p, key, job, filaments, g) {
    var pk = printerKey(c);
    var pct = Math.max(job ? job.pct : 0, num(p.mc_percent));
    var frac = g === 'FINISH' ? 1 : Math.min(1, pct / 100);
    var entry = {
      t: Date.now(), printer: c.cfg.name || pk, serial: pk, name: p.subtask_name || '', result: g,
      pct: Math.round(frac * 100), items: []
    };
    if (!filaments) entry.unknown = true;
    else {
      var used = filaments.filter(function (f) { return num(f.grams) > 0; });
      used.forEach(function (f) {
        var grams = round1(num(f.grams) * frac);
        if (grams <= 0) return;
        var slot = matchSlot(p, job && job.trays, f, used.length === 1);
        var sp = slot == null ? null : spool(data.slots[pk + ':' + slot]);
        if (sp) { sp.used = round1(num(sp.used) + grams); sp.lastUsed = Date.now(); }
        entry.items.push({
          spool: sp ? sp.id : null, label: sp ? spoolName(sp) : (f.type || 'Filament') + ' (no spool linked)',
          color: sp ? sp.colorHex : core.colorOf(String(f.color || '').replace('#', '')),
          slot: slot == null ? '?' : slotLabel(slot), g: grams,
          cost: sp && num(sp.price) > 0 && num(sp.initial) > 0 ? grams / num(sp.initial) * num(sp.price) : 0
        });
      });
    }
    if (entry.items.length || entry.unknown || g === 'FINISH') data.history.unshift(entry);
    if (data.history.length > 500) data.history.length = 500;
    data.done.push(key);
    if (data.done.length > 300) data.done.splice(0, data.done.length - 300);
    delete data.jobs[pk];
    c.filErrorSince = 0;
    save();
    core.rerender();
    if ($('library') && !$('library').classList.contains('hidden')) renderLibrary();
  }

  // ------------------------------------------------------ slot picker (dashboard)

  function pickSpool(c, slot) {
    slot = num(slot);
    var p = (c.state && c.state.print) || {};
    var tray = null;
    trays(p).forEach(function (t) { if (t.slot === slot) tray = t; });
    var current = data.slots[slotKey(c, slot)];
    var box = el('div', 'picker');
    if (tray && tray.rfid) {
      box.appendChild(el('p', 'hint', 'This is a Bambu spool with an RFID chip: PrintWatch links it automatically.'));
    }
    var list = data.spools.filter(function (sp) { return !sp.archived; });
    var type = tray ? String(tray.type).toUpperCase() : '';
    list.sort(function (a, b) {
      var ma = String(a.material).toUpperCase() === type ? 0 : 1, mb = String(b.material).toUpperCase() === type ? 0 : 1;
      return ma - mb || colorDist(a.colorHex, tray && tray.color) - colorDist(b.colorHex, tray && tray.color);
    });
    if (!list.length) box.appendChild(el('p', 'hint', 'Your filament library is empty. Add this spool:'));
    list.forEach(function (sp) {
      var row = spoolRow(sp, sp.id === current);
      row.addEventListener('click', function () { core.closeDialog(); assign(c, slot, sp.id); });
      box.appendChild(row);
    });
    var buttons = [{ label: 'Cancel' }];
    if (current) buttons.unshift({ label: 'Unlink', cls: 'danger', fn: function () { assign(c, slot, null); } });
    buttons.push({
      label: '+ New spool', cls: 'primary', fn: function () {
        catalogPicker({
          material: tray ? tray.type : 'PLA', sub: tray ? tray.sub : '', colorHex: (tray && tray.color) || '#ffffff'
        }, function (sp) { assign(c, slot, sp.id); });
      }
    });
    core.dialog('Spool in ' + (c.cfg.name || 'printer') + ' · ' + slotLabel(slot), box, buttons, true);
  }

  function spoolRow(sp, selected) {
    var row = el('button', 'spoolrow' + (selected ? ' sel' : ''));
    var sw = el('i', 'sw');
    sw.style.background = sp.colorHex || '#888';
    row.appendChild(sw);
    var txt = el('span', 'nm');
    txt.appendChild(el('b', null, spoolTitle(sp)));
    if (sp.colorName) txt.appendChild(el('small', null, sp.colorName));
    row.appendChild(txt);
    var r = remaining(sp);
    row.appendChild(el('span', 'rem' + (r < data.low ? ' low' : ''), Math.round(r) + ' g'));
    return row;
  }

  // --------------------------------------------------------- spool editor

  function field(label, input) {
    var l = el('label', 'fld', label);
    l.appendChild(input);
    return l;
  }
  function input(type, value, attrs) {
    var i = el('input');
    i.type = type;
    if (value != null) i.value = value;
    Object.keys(attrs || {}).forEach(function (k) { i.setAttribute(k, attrs[k]); });
    return i;
  }

  function editSpool(sp, defaults, done) {
    var isNew = !sp;
    var d = sp || {};
    defaults = defaults || {};
    var form = el('div', 'form2');
    if (defaults.fromCatalog) {
      var tip = el('p', 'hint full', 'Filled in from the catalogue. Add the price if you like, and change ' +
        '"Filament left now" if this spool is not new.');
      form.appendChild(tip);
    }
    var fBrand = input('text', d.brand || defaults.brand || '', { placeholder: 'e.g. Bambu Lab, eSun, Polymaker' });
    var fMat = input('text', d.material || defaults.material || 'PLA', { list: 'materialList' });
    var fSub = input('text', d.sub || defaults.sub || '', { placeholder: 'e.g. PLA Basic, Matte' });
    var fColor = input('color', d.colorHex || defaults.colorHex || '#ffffff');
    var fColorName = input('text', d.colorName || defaults.colorName || '', { placeholder: 'e.g. Jade White' });
    var fInitial = input('number', d.initial || defaults.initial || 1000, { inputmode: 'decimal', min: 0 });
    var fTare = input('number', d.tare != null ? d.tare : (defaults.tare != null ? defaults.tare : 250), { inputmode: 'decimal', min: 0 });
    var remainShown = sp ? String(Math.round(remaining(sp))) : '';
    var fRemain = input('number', remainShown, { inputmode: 'decimal', min: 0, placeholder: 'full' });
    var fPrice = input('number', d.price || '', { inputmode: 'decimal', min: 0, step: '0.01', placeholder: 'optional' });
    var fNote = input('text', d.note || '', { placeholder: 'optional' });
    var fLoc = el('select');
    var currentLoc = sp ? locationOf(sp.id) : (defaults.location || 'place:Shelf');
    locationOptions().forEach(function (o) {
      var opt = el('option', null, o.label);
      opt.value = o.value;
      if (o.value === currentLoc) opt.selected = true;
      fLoc.appendChild(opt);
    });
    var fArch = input('checkbox');
    fArch.checked = !!d.archived;

    form.appendChild(field('Brand', fBrand));
    form.appendChild(field('Material', fMat));
    form.appendChild(field('Product line', fSub));
    var colorRow = el('label', 'fld', 'Colour');
    var cr = el('div', 'colorrow');
    cr.appendChild(fColor);
    cr.appendChild(fColorName);
    colorRow.appendChild(cr);
    form.appendChild(colorRow);
    form.appendChild(field('Filament weight when new (g)', fInitial));
    form.appendChild(field('Empty spool weight (g)', fTare));
    form.appendChild(field('Filament left now (g)', fRemain));
    form.appendChild(field('Price of the spool' + (data.currency ? ' (' + data.currency + ')' : ''), fPrice));
    var locF = field('Location', fLoc);
    locF.classList.add('full');
    if (!done) form.appendChild(locF); // when adding from a dashboard slot, that slot is the location
    var noteF = field('Note', fNote);
    noteF.classList.add('full');
    form.appendChild(noteF);
    if (!isNew) {
      var al = el('label', 'check full');
      al.appendChild(fArch);
      al.appendChild(document.createTextNode(' Used up / archived (hide from lists)'));
      form.appendChild(al);
    }

    var buttons = [{ label: 'Cancel' }];
    if (!isNew) {
      buttons.unshift({
        label: 'Delete', cls: 'danger', fn: function () {
          core.confirmDialog('Delete this spool?', spoolName(sp) + ' will be removed from the library. Print history stays.',
            'Delete', true, function () { commit({ type: 'delete', id: sp.id }); });
        }
      });
    }
    buttons.push({
      label: 'Save', cls: 'primary', fn: function () {
        var s2 = {
          id: sp ? sp.id : uid(),
          brand: fBrand.value.trim(),
          material: fMat.value.trim() || 'PLA',
          sub: fSub.value.trim(),
          colorHex: fColor.value,
          colorName: fColorName.value.trim(),
          initial: Math.max(1, num(fInitial.value, 1000)),
          tare: Math.max(0, num(fTare.value, 0)),
          price: num(fPrice.value, 0),
          note: fNote.value.trim(),
          archived: fArch.checked
        };
        if (isNew) s2.created = Date.now();
        // only touch the amount when it was changed here, so a print booked meanwhile is kept
        var left = fRemain.value !== remainShown ? (fRemain.value === '' ? s2.initial : num(fRemain.value)) : null;
        var loc = !done && (fLoc.value !== currentLoc || !sp) ? fLoc.value : null;
        commit({ type: 'upsert', spool: s2, remaining: left, location: loc }, function () { if (done) done(spool(s2.id)); });
      }
    });
    core.dialog(isNew ? 'New spool' : 'Edit spool', form, buttons, true);
  }

  function weighSpool(sp) {
    var box = el('div');
    box.appendChild(el('p', 'hint', 'Put the spool on a scale and enter the total weight, spool included. ' +
      'The empty spool weighs ' + Math.round(num(sp.tare)) + ' g (change that under Edit).'));
    var w = input('number', '', { inputmode: 'decimal', min: 0, placeholder: 'grams' });
    w.className = 'bignum';
    var out = el('p', 'hint');
    function calc() {
      var left = num(w.value) - num(sp.tare);
      out.textContent = w.value === '' ? '' : 'Filament left: ' + Math.max(0, Math.round(left)) + ' g';
    }
    w.addEventListener('input', calc);
    box.appendChild(w);
    box.appendChild(out);
    core.dialog('Weigh ' + spoolName(sp), box, [{ label: 'Cancel' }, {
      label: 'Save', cls: 'primary', fn: function () {
        if (w.value === '') return;
        var left = Math.max(0, num(w.value) - num(sp.tare));
        commit({ type: 'upsert', spool: { id: sp.id, weighed: Date.now() }, remaining: left });
      }
    }]);
    setTimeout(function () { w.focus(); }, 50);
  }



  // ------------------------------------------------- changes (local or from a computer)

  var remotePrinters = [];

  function printerList() {
    if (core.REMOTE) return remotePrinters;
    return core.cards().map(function (c) {
      var p = c.state && c.state.print;
      var known = p ? trays(p).map(function (t) { return t.slot; }) : [];
      if (!p || !p.ams) known = known.length ? known : [0, 1, 2, 3, 254]; // not connected yet: assume one AMS
      if (known.indexOf(254) < 0) known.push(254);
      return { key: printerKey(c), name: c.cfg.name || '', slots: known };
    });
  }

  function slotLongName(slot) {
    slot = num(slot, -1);
    if (slot === 254) return 'External spool';
    var unit = Math.floor(slot / 4), n = slot % 4 + 1;
    return (unit ? 'AMS ' + (unit + 1) + ' slot ' : 'AMS slot ') + n;
  }

  /** All places a spool can be: every printer slot, then the shelf and your own places. */
  function locationOptions() {
    var opts = [];
    printerList().forEach(function (pr) {
      (pr.slots || [0, 1, 2, 3, 254]).forEach(function (sl) {
        opts.push({ value: 'slot:' + pr.key + ':' + sl, label: (pr.name || pr.key) + ' \u2013 ' + slotLongName(sl) });
      });
    });
    places().forEach(function (pl) { opts.push({ value: 'place:' + pl, label: pl }); });
    return opts;
  }
  function places() {
    var list = (data.places && data.places.length ? data.places : ['Shelf']).slice();
    if (list.indexOf('Shelf') < 0) list.unshift('Shelf');
    return list;
  }
  function locationOf(id) {
    var keys = Object.keys(data.slots);
    for (var i = 0; i < keys.length; i++) if (data.slots[keys[i]] === id) return 'slot:' + keys[i];
    var sp = spool(id);
    return 'place:' + ((sp && sp.place) || 'Shelf');
  }

  /** Applies one change to the library. The tablet is the only place this runs. */
  function apply(op) {
    if (op.type === 'upsert' && op.spool && op.spool.id) {
      var sp = spool(op.spool.id);
      if (!sp) { sp = { id: op.spool.id, used: 0, created: Date.now() }; data.spools.push(sp); }
      Object.keys(op.spool).forEach(function (k) { if (k !== 'used' && k !== 'id') sp[k] = op.spool[k]; });
      if (op.remaining != null) sp.used = round1(Math.max(0, num(sp.initial) - num(op.remaining)));
      if (op.location) moveTo(sp, op.location);
    } else if (op.type === 'delete') {
      data.spools = data.spools.filter(function (x) { return x.id !== op.id; });
      Object.keys(data.slots).forEach(function (k) { if (data.slots[k] === op.id) delete data.slots[k]; });
    } else if (op.type === 'settings') {
      if (Array.isArray(op.places)) data.places = op.places;
      if (op.low != null) data.low = num(op.low, 150);
      if (op.currency != null) data.currency = String(op.currency).slice(0, 3);
    } else if (op.type === 'replace' && op.data && Array.isArray(op.data.spools)) {
      data = op.data;
      load2();
    }
  }

  /** Puts a spool in a printer slot ("slot:<printer>:<slot>") or a place ("place:Shelf"). */
  function moveTo(sp, loc) {
    Object.keys(data.slots).forEach(function (k) { if (data.slots[k] === sp.id) delete data.slots[k]; });
    if (loc.indexOf('slot:') === 0) {
      var key = loc.slice(5);
      var previous = spool(data.slots[key]);
      if (previous && previous.id !== sp.id) previous.place = 'Shelf';
      data.slots[key] = sp.id;
      delete sp.place;
    } else {
      sp.place = loc.slice(6) || 'Shelf';
    }
  }

  function commit(op, after) {
    if (core.REMOTE) {
      fetch('/api/op', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(op) })
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(function (res) { remoteData(res); renderLibrary(); if (after) after(); })
        .catch(function (e) { offline('Could not save: ' + e.message); });
      return;
    }
    apply(op);
    save();
    core.rerender();
    renderLibrary();
    if (after) after();
  }

  /** For the web server on the tablet: the library as JSON, and changes coming from a computer. */
  function remoteGet() {
    return JSON.stringify({ data: data, printers: printerList() });
  }
  function remoteOp(opJson) {
    apply(typeof opJson === 'string' ? JSON.parse(opJson) : opJson);
    save();
    core.rerender();
    if (isOpen()) renderLibrary();
    return remoteGet();
  }

  // --- the computer's side
  function remoteData(res) {
    data = res.data || {};
    load2();
    remotePrinters = res.printers || [];
    $('remoteStatus').textContent = 'Connected to PrintWatch on the tablet';
    $('remoteStatus').classList.remove('err');
  }
  function offline(msg) {
    $('remoteStatus').textContent = msg + ' — is the tablet on, with PrintWatch open?';
    $('remoteStatus').classList.add('err');
  }
  function remoteRefresh() {
    if (document.querySelector('.dialog:not(.hidden)')) return; // don't redraw under an open form
    fetch('/api/library', { cache: 'no-store' })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (res) { remoteData(res); renderLibrary(); })
      .catch(function (e) { offline('Lost contact'); });
  }

  // ------------------------------------------------------------- catalogue

  var catalogPromise = null;
  var POPULAR = ['Bambu Lab', 'Polymaker', 'eSun', 'Sunlu', 'Prusament', 'Elegoo', 'Overture', 'Jayo', 'Creality',
    'Anycubic', 'Hatchbox', 'Eryone', 'Spectrum', 'Fiberlogy', 'Extrudr', 'Das Filament', 'Devil Design', '3DJake'];

  function loadCatalog() {
    if (!catalogPromise) {
      catalogPromise = fetch('catalog.json').then(function (r) { return r.json(); })
        .catch(function () { catalogPromise = null; return { brands: [] }; });
    }
    return catalogPromise;
  }

  function lineName(prod) {
    var line = prod[0], mat = prod[1];
    if (!line) return mat;
    return line.toUpperCase().indexOf(mat.toUpperCase()) >= 0 ? line : mat + ' ' + line;
  }

  /** Pick a spool from the built-in catalogue (SpoolmanDB), then fine-tune it in the editor. */
  function catalogPicker(defaults, done) {
    var box = el('div', 'catalog');
    var search = input('search', '', { placeholder: 'Search: brand, material or colour (e.g. "bambu pla basic white")' });
    search.className = 'catsearch';
    var crumbs = el('div', 'crumbs');
    var list = el('div', 'catlist');
    box.appendChild(search);
    box.appendChild(crumbs);
    box.appendChild(list);
    core.dialog('Add a spool', box, [
      { label: 'Cancel' },
      { label: 'Custom spool…', cls: 'ghost', fn: function () { editSpool(null, defaults, done); } }
    ], true);

    var cat = null, brand = null, prod = null;
    loadCatalog().then(function (c) { cat = c; show(); });
    list.appendChild(el('p', 'hint', 'Loading catalogue…'));
    search.addEventListener('input', function () { brand = null; prod = null; show(); });

    function crumb(text, fn) {
      var b = el('button', 'crumb', text);
      b.addEventListener('click', fn);
      crumbs.appendChild(b);
    }

    function show() {
      if (!cat) return;
      list.textContent = '';
      crumbs.textContent = '';
      var q = search.value.trim().toLowerCase();
      if (q.length >= 2) return showSearch(q);
      if (prod) {
        crumb('All brands', function () { brand = null; prod = null; show(); });
        crumb(brand[0], function () { prod = null; show(); });
        crumbs.appendChild(el('span', 'crumbnow', lineName(prod)));
        return showColors(brand, prod);
      }
      if (brand) {
        crumb('All brands', function () { brand = null; show(); });
        crumbs.appendChild(el('span', 'crumbnow', brand[0]));
        return showProducts(brand);
      }
      showBrands();
    }

    function showBrands() {
      var grid = el('div', 'brandgrid');
      var sorted = cat.brands.slice().sort(function (a, b) {
        var pa = POPULAR.indexOf(a[0]), pb = POPULAR.indexOf(b[0]);
        pa = pa < 0 ? 999 : pa; pb = pb < 0 ? 999 : pb;
        return pa - pb || a[0].localeCompare(b[0]);
      });
      sorted.forEach(function (b) {
        var btn = el('button', 'brand', b[0]);
        btn.addEventListener('click', function () { brand = b; show(); });
        grid.appendChild(btn);
      });
      list.appendChild(grid);
      list.appendChild(el('p', 'hint small', 'Catalogue: ' + (cat.source || 'SpoolmanDB') + (cat.built ? ', ' + cat.built : '')));
    }

    function showProducts(b) {
      var want = String((defaults && defaults.material) || '').toUpperCase();
      var prods = b[1].slice().sort(function (x, y) {
        var mx = x[1].toUpperCase() === want ? 0 : 1, my = y[1].toUpperCase() === want ? 0 : 1;
        return mx - my || lineName(x).localeCompare(lineName(y));
      });
      prods.forEach(function (p) {
        var row = el('button', 'prodrow');
        row.appendChild(el('b', null, lineName(p)));
        var dots = el('span', 'dots');
        p[3].slice(0, 14).forEach(function (c) { var d = el('i'); d.style.background = c[1]; dots.appendChild(d); });
        row.appendChild(dots);
        row.appendChild(el('small', null, p[3].length + ' colours'));
        row.addEventListener('click', function () { prod = p; show(); });
        list.appendChild(row);
      });
    }

    function showColors(b, p) {
      var grid = el('div', 'colorgrid');
      p[3].forEach(function (c) {
        var btn = el('button', 'colorbtn');
        var sw = el('i');
        sw.style.background = c[1];
        btn.appendChild(sw);
        btn.appendChild(el('span', null, c[0]));
        btn.addEventListener('click', function () { choose(b, p, c); });
        grid.appendChild(btn);
      });
      list.appendChild(grid);
    }

    function showSearch(q) {
      var words = q.split(/\s+/);
      var hits = [];
      cat.brands.forEach(function (b) {
        b[1].forEach(function (p) {
          var base = (b[0] + ' ' + lineName(p)).toLowerCase();
          p[3].forEach(function (c) {
            if (hits.length >= 80) return;
            var text = base + ' ' + c[0].toLowerCase();
            for (var i = 0; i < words.length; i++) if (text.indexOf(words[i]) < 0) return;
            hits.push([b, p, c]);
          });
        });
      });
      if (!hits.length) list.appendChild(el('p', 'hint', 'Nothing found. Try fewer words, or add a custom spool.'));
      hits.forEach(function (h) {
        var row = el('button', 'spoolrow');
        var sw = el('i', 'sw');
        sw.style.background = h[2][1];
        row.appendChild(sw);
        var nm = el('span', 'nm');
        nm.appendChild(el('b', null, h[0][0] + ' ' + lineName(h[1])));
        nm.appendChild(el('small', null, h[2][0]));
        row.appendChild(nm);
        row.addEventListener('click', function () { choose(h[0], h[1], h[2]); });
        list.appendChild(row);
      });
    }

    function choose(b, p, c) {
      var weights = p[2];
      function go(w) {
        core.closeDialog();
        editSpool(null, {
          brand: b[0], material: p[1], sub: lineName(p), colorHex: c[1], colorName: c[0],
          initial: w[0], tare: w[1] == null ? 250 : w[1], fromCatalog: true
        }, done);
      }
      if (weights.length <= 1) return go(weights[0] || [1000, null]);
      list.textContent = '';
      list.appendChild(el('p', 'hint', 'Which spool size?'));
      weights.forEach(function (w) {
        var btn = el('button', 'prodrow', (w[0] >= 1000 ? (w[0] / 1000) + ' kg' : w[0] + ' g') +
          (w[1] != null ? '  (empty spool ' + w[1] + ' g)' : ''));
        btn.addEventListener('click', function () { go(w); });
        list.appendChild(btn);
      });
    }
  }

  // --------------------------------------------------------------- library

  function open() {
    tab = 'spools';
    renderLibrary();
    $('library').classList.remove('hidden');
    $('library').scrollTop = 0;
  }
  function close() { $('library').classList.add('hidden'); }
  function isOpen() { return !$('library').classList.contains('hidden'); }

  function renderLibrary() {
    if (!$('library')) return;
    Array.prototype.forEach.call(document.querySelectorAll('#library .tabs button'), function (b) {
      b.classList.toggle('sel', b.getAttribute('data-tab') === tab);
    });
    $('libSpools').classList.toggle('hidden', tab !== 'spools');
    $('libHistory').classList.toggle('hidden', tab !== 'history');
    if (tab === 'spools') renderSpools(); else renderHistory();
  }

  function renderSpools() {
    var listEl = $('spoolList');
    listEl.textContent = '';
    var showAll = $('showArchived').checked;
    var list = data.spools.filter(function (sp) { return showAll || !sp.archived; });
    list.sort(function (a, b) {
      var la = whereLoaded(a.id) ? 0 : 1, lb = whereLoaded(b.id) ? 0 : 1;
      return la - lb || spoolName(a).localeCompare(spoolName(b));
    });
    var total = 0;
    data.spools.forEach(function (sp) { if (!sp.archived) total += remaining(sp); });
    $('libTotals').textContent = data.spools.filter(function (sp) { return !sp.archived; }).length + ' spools · ' +
      (total >= 1000 ? (total / 1000).toFixed(1) + ' kg' : Math.round(total) + ' g') + ' of filament';
    if (!list.length) {
      listEl.appendChild(el('p', 'hint', 'No spools yet. Bambu spools with an RFID chip appear here by themselves as soon as ' +
        'they are in an AMS. Add other spools with "+ Add spool", or tap a slot on the dashboard.'));
      return;
    }
    list.forEach(function (sp) {
      var card = el('div', 'spool' + (sp.archived ? ' archived' : ''));
      var top = el('div', 'sp-top');
      var sw = el('div', 'sp-sw');
      sw.style.background = sp.colorHex || '#888';
      top.appendChild(sw);
      var nm = el('div', 'sp-name');
      nm.appendChild(el('b', null, spoolTitle(sp)));
      nm.appendChild(el('span', null, [sp.colorName, sp.rfid ? 'RFID' : ''].filter(Boolean).join(' · ')));
      top.appendChild(nm);
      card.appendChild(top);
      var r = remaining(sp), pct = num(sp.initial) > 0 ? Math.min(100, r / num(sp.initial) * 100) : 0;
      var bar = el('div', 'sp-bar');
      var fill = el('i');
      fill.style.width = pct + '%';
      fill.style.background = sp.colorHex || '#888';
      bar.appendChild(fill);
      card.appendChild(bar);
      var line = el('div', 'sp-line');
      line.appendChild(el('b', r < data.low ? 'low' : '', Math.round(r) + ' g'));
      line.appendChild(document.createTextNode(' left of ' + Math.round(num(sp.initial)) + ' g'));
      card.appendChild(line);
      card.appendChild(el('div', 'sp-where' + (whereLoaded(sp.id) ? ' loaded' : ''), '\ud83d\udccd ' + whereIs(sp.id)));
      var acts = el('div', 'sp-acts');
      var bw = el('button', 'ghost', 'Weigh');
      bw.addEventListener('click', function () { weighSpool(sp); });
      var be = el('button', 'ghost', 'Edit');
      be.addEventListener('click', function () { editSpool(sp); });
      acts.appendChild(bw);
      acts.appendChild(be);
      card.appendChild(acts);
      listEl.appendChild(card);
    });
  }

  function renderHistory() {
    var box = $('histList');
    box.textContent = '';
    var monthAgo = Date.now() - 30 * 86400000;
    var g30 = 0, n30 = 0, cost30 = 0;
    data.history.forEach(function (h) {
      if (h.t < monthAgo) return;
      n30++;
      (h.items || []).forEach(function (it) { g30 += num(it.g); cost30 += num(it.cost); });
    });
    $('histSummary').textContent = 'Last 30 days: ' + n30 + ' prints · ' +
      (g30 >= 1000 ? (g30 / 1000).toFixed(2) + ' kg' : Math.round(g30) + ' g') + (cost30 > 0 ? ' · ' + money(cost30) : '');
    if (!data.history.length) {
      box.appendChild(el('p', 'hint', 'Finished prints appear here with the filament they used.'));
      return;
    }
    data.history.slice(0, 200).forEach(function (h) {
      var row = el('div', 'hrow');
      var d = new Date(h.t);
      var head = el('div', 'hhead');
      head.appendChild(el('b', null, h.name || 'Print'));
      head.appendChild(el('span', 'hmeta', d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) + ' ' +
        core.fmtClock(d) + ' · ' + h.printer + (h.result === 'FAILED' ? ' · stopped at ' + h.pct + '%' : '')));
      row.appendChild(head);
      if (h.unknown) row.appendChild(el('div', 'hitem', 'Filament use unknown (the print file could not be read from the printer)'));
      var tg = 0, tc = 0;
      (h.items || []).forEach(function (it) {
        var item = el('div', 'hitem');
        var sw = el('i');
        sw.style.background = it.color || '#888';
        item.appendChild(sw);
        item.appendChild(document.createTextNode(it.label + ' (' + it.slot + ')'));
        item.appendChild(el('b', null, round1(it.g) + ' g' + (it.cost ? ' · ' + money(it.cost) : '')));
        row.appendChild(item);
        tg += num(it.g); tc += num(it.cost);
      });
      if ((h.items || []).length > 1) row.appendChild(el('div', 'htotal', 'Total ' + round1(tg) + ' g' + (tc ? ' · ' + money(tc) : '')));
      box.appendChild(row);
    });
  }

  function backupDialog() {
    var box = el('div');
    box.appendChild(el('p', 'hint', 'Copy this text somewhere safe (e.g. an e-mail to yourself). ' +
      'To restore, paste a backup here and tap Restore.'));
    var ta = el('textarea', 'backup');
    ta.value = JSON.stringify(data);
    ta.addEventListener('focus', function () { ta.select(); });
    box.appendChild(ta);
    core.dialog('Backup / restore', box, [
      { label: 'Close' },
      {
        label: 'Restore', cls: 'primary', fn: function () {
          try {
            var d = JSON.parse(ta.value);
            if (!d || !Array.isArray(d.spools)) throw new Error('no spools');
            commit({ type: 'replace', data: d });
          } catch (e) {
            core.dialog('Restore failed', 'That text is not a PrintWatch backup.', [{ label: 'OK' }]);
          }
        }
      }
    ], true);
  }
  function load2() {
    if (data.currency == null) data.currency = '';
    ['spools', 'history', 'done'].forEach(function (k) { if (!Array.isArray(data[k])) data[k] = []; });
    ['slots', 'jobs'].forEach(function (k) { if (!data[k] || typeof data[k] !== 'object') data[k] = {}; });
    if (!data.since) data.since = Date.now();
    if (data.low == null) data.low = 150;
  }

  function libSettings() {
    var form = el('div', 'form2');
    var low = input('number', data.low, { inputmode: 'decimal', min: 0 });
    var cur = input('text', data.currency || '', { placeholder: 'e.g. € or $', maxlength: 3 });
    form.appendChild(field('Warn when a spool has less than (g)', low));
    form.appendChild(field('Currency symbol for prices', cur));
    var pl = input('text', places().join(', '), { placeholder: 'Shelf, Dry box, Drawer' });
    var plF = field('Your own places, separated by commas (besides the printers)', pl);
    plF.classList.add('full');
    form.appendChild(plF);
    core.dialog('Filament settings', form, [{ label: 'Cancel' }, {
      label: 'Save', cls: 'primary', fn: function () {
        var list = pl.value.split(',').map(function (x) { return x.trim(); }).filter(Boolean);
        commit({ type: 'settings', low: Math.max(0, num(low.value, 150)), currency: cur.value.trim(), places: list });
      }
    }], true);
  }

  // ------------------------------------------------------------------ demo

  function demoData() {
    var t = Date.now();
    return {
      since: t - 86400000 * 40, low: 150, currency: '€', jobs: {}, done: [],
      spools: [
        { id: 'd1', brand: 'Bambu Lab', material: 'PLA', sub: 'PLA Basic', colorHex: '#f2f2f2', colorName: 'Jade White', initial: 1000, used: 280, tare: 250, price: 19.99 },
        { id: 'd2', brand: 'Bambu Lab', material: 'PETG', sub: 'PETG HF', colorHex: '#a6a9aa', colorName: 'Gray', initial: 1000, used: 550, tare: 250, price: 21.99 },
        { id: 'd3', brand: 'eSun', material: 'PETG', sub: '', colorHex: '#161616', colorName: 'Black', initial: 1000, used: 100, tare: 220, price: 17.5 },
        { id: 'd4', brand: 'Polymaker', material: 'PETG', sub: 'PolyLite', colorHex: '#1e4fd8', colorName: 'Blue', initial: 1000, used: 880, tare: 200, price: 22 },
        { id: 'd5', brand: 'Bambu Lab', material: 'PLA', sub: 'PLA Basic', colorHex: '#00ae42', colorName: 'Bambu Green', initial: 1000, used: 370, tare: 250, price: 19.99 },
        { id: 'd6', brand: 'Bambu Lab', material: 'PLA', sub: 'PLA Basic', colorHex: '#ffd000', colorName: 'Sunflower Yellow', initial: 1000, used: 0, tare: 250, price: 19.99 },
        { id: 'd7', brand: 'Sunlu', material: 'PLA', sub: 'PLA+', colorHex: '#c12e1f', colorName: 'Red', initial: 1000, used: 660, tare: 160, price: 15.99 },
        { id: 'd8', brand: 'Bambu Lab', material: 'TPU', sub: 'TPU 95A', colorHex: '#3a3a3a', colorName: 'Black', initial: 1000, used: 120, tare: 250, price: 39.99 }
      ],
      slots: {
        '01P00A000000001:0': 'd1', '01P00A000000001:1': 'd2', '01P00A000000001:2': 'd3', '01P00A000000001:3': 'd4',
        '01P00A000000002:0': 'd5', '01P00A000000002:1': 'd6', '01P00A000000002:3': 'd7'
      },
      history: [
        { t: t - 3600000 * 5, printer: 'P1S Left', name: 'drawer-divider', result: 'FINISH', pct: 100, items: [{ label: 'Bambu Lab PETG HF · Gray', color: '#a6a9aa', slot: 'A2', g: 142.6, cost: 3.14 }] },
        { t: t - 3600000 * 26, printer: 'P1S Right', name: 'moose-keychain x12', result: 'FINISH', pct: 100, items: [{ label: 'Bambu Lab PLA Basic · Bambu Green', color: '#00ae42', slot: 'A1', g: 61.2, cost: 1.22 }, { label: 'Bambu Lab PLA Basic · Sunflower Yellow', color: '#ffd000', slot: 'A2', g: 3.9, cost: 0.08 }] },
        { t: t - 3600000 * 30, printer: 'P1S Left', name: 'cable-clip', result: 'FAILED', pct: 35, items: [{ label: 'eSun PETG · Black', color: '#161616', slot: 'A3', g: 8.4, cost: 0.15 }] }
      ]
    };
  }

  // ------------------------------------------------------------------ init

  function init(c) {
    core = c;
    load();
    $('openLibrary').addEventListener('click', open);
    $('libClose').addEventListener('click', close);
    $('addSpool').addEventListener('click', function () { catalogPicker({}, null); });
    $('showArchived').addEventListener('change', renderSpools);
    $('libBackup').addEventListener('click', backupDialog);
    $('libSettings').addEventListener('click', libSettings);
    Array.prototype.forEach.call(document.querySelectorAll('#library .tabs button'), function (b) {
      b.addEventListener('click', function () { tab = b.getAttribute('data-tab'); renderLibrary(); });
    });
    if (core.REMOTE) {
      open();
      remoteRefresh();
      setInterval(remoteRefresh, 15000);
    }
    var dl = $('materialList');
    MATERIALS.forEach(function (m) { var o = el('option'); o.value = m; dl.appendChild(o); });
  }

  window.PWFil = {
    init: init, track: track, trays: trays, trayLabel: trayLabel, pickSpool: pickSpool,
    open: open, close: close, isOpen: isOpen, version: function () { return ver; },
    remoteGet: remoteGet, remoteOp: remoteOp,
    _data: function () { return data; } // for tests
  };
})();
