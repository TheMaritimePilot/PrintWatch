#!/usr/bin/env python3
"""Builds app/src/main/assets/web/catalog.json from SpoolmanDB (https://github.com/Donkie/SpoolmanDB, MIT).

Usage: python3 tools/build_catalog.py /path/to/SpoolmanDB

Output (compact, 1.75 mm only):
  {"source": ..., "built": "YYYY-MM-DD",
   "brands": [[brand, [[line, material, [[weight, spool_weight], ...], [[color_name, "#rrggbb"], ...], nozzle, bed], ...]], ...]}
"""
import datetime, glob, json, os, sys

src = sys.argv[1]
out = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'web', 'catalog.json')
brands = []
for path in sorted(glob.glob(os.path.join(src, 'filaments', '*.json'))):
    d = json.load(open(path, encoding='utf-8'))
    products = []
    for f in d['filaments']:
        if f.get('diameters') and 1.75 not in f['diameters']:
            continue
        line = f['name'].replace('{color_name}', '').strip(' -–')
        weights = [[w['weight'], w.get('spool_weight')] for w in f.get('weights', [])] or [[1000, None]]
        colors = []
        for c in f.get('colors', []):
            hx = c.get('hex') or (c.get('hexes') or [None])[0]
            if hx:
                colors.append([c['name'], '#' + hx.lower()[:6]])
        if colors:
            products.append([line, f['material'], weights, colors, f.get('extruder_temp'), f.get('bed_temp')])
    if products:
        brands.append([d['manufacturer'], products])
brands.sort(key=lambda b: b[0].lower())
cat = {'source': 'SpoolmanDB by Donkie (MIT license) https://github.com/Donkie/SpoolmanDB',
       'built': datetime.date.today().isoformat(), 'brands': brands}
with open(out, 'w', encoding='utf-8') as fh:
    json.dump(cat, fh, ensure_ascii=False, separators=(',', ':'))
n = sum(len(c[3]) for b in brands for c in b[1])
print(f'{len(brands)} brands, {sum(len(b[1]) for b in brands)} product lines, {n} colours -> {os.path.getsize(out)//1024} KB')
