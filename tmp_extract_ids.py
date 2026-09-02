import re, sys
sys.stdout.reconfigure(encoding='utf-8')
p = r'e:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\coshare_patientrecord_sys_backend\src\main\resources\db\migration\V32__inventory_quota_versions_and_reviews.sql'
src = open(p, encoding='utf-8').read()
pat = re.compile(r"VALUES \('(qv1-[^']+)', 'quota-v1', '([^']+)', '([^']+)', (\d+), '([^']*)', '([^']*)', '([^']*)', '([^']*)', ([^,]+), ([^,]+), '([^']+)', (\d)\)")
rows = pat.findall(src)
print('total:', len(rows))
for r in rows:
    rid, dkey, dname, srow, sgroup, ctype, mat, unit, sq, fa, scope, en = r
    print(f'{rid}|{dkey}|{srow}|{sgroup}|{mat}|{unit}|{sq.strip()}')
