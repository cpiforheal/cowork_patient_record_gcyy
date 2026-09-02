import re, sys
sys.stdout.reconfigure(encoding='utf-8')
p = r'e:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\coshare_patientrecord_sys_backend\src\main\resources\db\migration\V32__inventory_quota_versions_and_reviews.sql'
src = open(p, encoding='utf-8').read()
pat = re.compile(r"VALUES \('([^']+)', 'quota-v1', '([^']+)', '([^']+)', (\d+), '([^']*)', '([^']*)', '([^']*)', '([^']*)', ([^,]+), ([^,]+), '([^']+)', (\d)\)")
rows = pat.findall(src)
print('total rule rows:', len(rows))
from collections import defaultdict
bydept = defaultdict(list)
for r in rows:
    bydept[r[2]].append((r[5], r[6], r[8], r[9].strip(), r[10].strip(), r[11]))
for dept, items in sorted(bydept.items()):
    print(f'=== {dept} ({len(items)}) ===')
    for sg, m, u, sq, fa, scope in items:
        print(f'  [{sg}] {m} | {u} | q={sq} adj={fa} {scope}')
