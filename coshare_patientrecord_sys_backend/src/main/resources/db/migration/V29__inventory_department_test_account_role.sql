INSERT INTO clinic_roles (id, role, name, raw_json)
VALUES ('role-inventory-reporter', 'inventory_reporter', '科室耗材填报测试账号', JSON_OBJECT('id','role-inventory-reporter','role','inventory_reporter','name','科室耗材填报测试账号'))
ON DUPLICATE KEY UPDATE name = VALUES(name), raw_json = VALUES(raw_json);
