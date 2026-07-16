SET NAMES utf8mb4;

INSERT INTO clinic_accounts (id, username, role, status, raw_json)
VALUES (
  'admin',
  'admin',
  'admin',
  '启用',
  JSON_OBJECT(
    'id', 'admin',
    'username', 'admin',
    'name', '管理员',
    'role', 'admin',
    'roleLabel', '管理员',
    'department', '信息/院办',
    'scope', '系统全局配置',
    'status', '启用',
    'createdAt', '2026-06-10 08:00:00',
    'updatedAt', '2026-06-10 08:00:00',
    'passwordHash', '$2a$10$YkQ1vqK4O8t/MhybAJoCiO4ZNF4ySvh0WiZ3IGCu5GNT2LDnJd9hy',
    'currentPassword', 'Init@Coshare2026!'
  )
)
ON DUPLICATE KEY UPDATE
  username = VALUES(username),
  role = VALUES(role),
  status = VALUES(status);
