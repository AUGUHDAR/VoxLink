import json, os, re
used = set(open('D:/桌面/voxlink/used_keys.txt', encoding='utf-8').read().splitlines())
translatable_used = set()
for k in used:
    if re.match(r'^voxlink\.[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$', k):
        translatable_used.add(k)
for k in used:
    if re.match(r'^voxlink\.[a-z][a-z0-9_]*$', k):
        translatable_used.add(k)
print('Translatable keys used:', len(translatable_used))

files = sorted([f for f in os.listdir('.') if f.endswith('.json')])
en = json.load(open('en_us.json', encoding='utf-8'))
en_keys = set(en.keys())

missing_in_en = sorted([k for k in translatable_used if k not in en_keys])
print('\nMissing from en_us.json (', len(missing_in_en), '):')
for m in missing_in_en:
    print(' ', m)

print()
for f in files:
    d = json.load(open(f, encoding='utf-8'))
    have = set(d.keys())
    miss = sorted([k for k in en_keys if k not in have])
    print(f, 'missing vs en:', len(miss))
    for m in miss[:5]:
        print(' ', m)
