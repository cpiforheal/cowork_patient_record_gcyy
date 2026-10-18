-- DIP 费用预警：病组映射表 + 当前费用快照
-- 背景：HIS 系统无导出能力，费用需由 HIS 截图/人工录入；病组由医生确定的主诊断匹配本标准表。
-- 单位：元；85%/95% 阈值为预估费用的 0.85 / 0.95 倍（前端与后端均按同一规则计算）。

CREATE TABLE IF NOT EXISTS dip_group_mapping (
  id VARCHAR(64) PRIMARY KEY,
  category_code VARCHAR(16) NOT NULL COMMENT '病组大类：K64/K60/K61/K62/L05',
  category_name VARCHAR(100) NOT NULL,
  group_code VARCHAR(64) NOT NULL COMMENT 'DIP 分组编码',
  group_name VARCHAR(200) NOT NULL,
  estimated_fee DECIMAL(12,2) NOT NULL COMMENT '病组预估费用',
  match_keywords VARCHAR(300) NOT NULL DEFAULT '' COMMENT '主诊断关键词，逗号分隔',
  keyword_priority INT NOT NULL DEFAULT 0 COMMENT '关键词越长越优先，此列做同长度时的优先级',
  sort_no INT NOT NULL DEFAULT 0,
  updated_at VARCHAR(32) NOT NULL DEFAULT '',
  UNIQUE KEY uk_dip_group_code (group_code),
  INDEX idx_dip_category (category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS dip_fee_snapshot (
  patient_id VARCHAR(64) PRIMARY KEY COMMENT 'clinic_patients.id',
  current_fee DECIMAL(12,2) NOT NULL DEFAULT 0,
  fee_source VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 手工录入 / SCREENSHOT 截图识别',
  updated_by VARCHAR(100) NOT NULL DEFAULT '',
  updated_at VARCHAR(32) NOT NULL DEFAULT '',
  INDEX idx_dip_fee_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 病组映射种子（来源：《常用病组核心诊断、操作及费用_85_95对照》）
INSERT IGNORE INTO dip_group_mapping
  (id, category_code, category_name, group_code, group_name, estimated_fee, match_keywords, keyword_priority, sort_no, updated_at)
VALUES
  ('dip-k64-004', 'K64', '痔疮与肛周静脉血栓形成', 'K64:49.4500x004', '内镜下内痔套扎治疗', 5062.00, '内痔,脱垂性内痔', 10, 1, '2026-10-18'),
  ('dip-k64-002', 'K64', '痔疮与肛周静脉血栓形成', 'K64:49.4600_45.2302', '痔切除术_电子结肠镜检查', 5021.00, '混合痔,环状混合痔', 20, 2, '2026-10-18'),
  ('dip-k64-003', 'K64', '痔疮与肛周静脉血栓形成', 'K64:49.4200', '痔注射', 4090.00, '脱垂性内痔', 30, 3, '2026-10-18'),
  ('dip-k64-004b', 'K64', '痔疮与肛周静脉血栓形成', 'K64:49.4600_49.5903', '痔切除术_肛门括约肌切开术', 4984.00, '环状混合痔', 25, 4, '2026-10-18'),
  ('dip-k64-014', 'K64', '痔疮与肛周静脉血栓形成', 'K64:49.4601_49.3906', '痔切除术伴肛门成形术_肛乳头切除术', 4405.00, '', 0, 5, '2026-10-18'),
  ('dip-k64-bszl', 'K64', '痔疮与肛周静脉血栓形成', 'K64:BSZL', '痔疮保守治疗', 3519.00, '痔病', 5, 6, '2026-10-18'),
  ('dip-k64-ss', 'K64', '痔疮与肛周静脉血栓形成', 'K64:SS', '痔疮相关手术组', 4579.00, '', 0, 7, '2026-10-18'),

  ('dip-k60-001', 'K60', '肛门及直肠区的裂和瘘', 'K60:49.1200', '肛门瘘管切除术', 4687.00, '肛瘘', 10, 11, '2026-10-18'),
  ('dip-k60-006', 'K60', '肛门及直肠区的裂和瘘', 'K60:49.1200_49.4600', '肛门瘘管切除术_痔切除术', 5566.00, '', 0, 12, '2026-10-18'),
  ('dip-k60-007', 'K60', '肛门及直肠区的裂和瘘', 'K60:49.3901_49.3906', '肛裂切除术_肛乳头切除术', 5056.00, '肛裂', 10, 13, '2026-10-18'),
  ('dip-k60-008', 'K60', '肛门及直肠区的裂和瘘', 'K60:49.3901_49.3906+49.4600', '肛裂切除术_肛乳头切除术+痔切除术', 5420.00, '', 0, 14, '2026-10-18'),
  ('dip-k60-009', 'K60', '肛门及直肠区的裂和瘘', 'K60:49.3901_49.5903', '肛裂切除术_肛门括约肌切开术', 5306.00, '', 0, 15, '2026-10-18'),
  ('dip-k60-ss', 'K60', '肛门及直肠区的裂和瘘', 'K60:SS', '裂和瘘相关手术组', 3951.00, '', 0, 16, '2026-10-18'),
  ('dip-k60-zlxcz', 'K60', '肛门及直肠区的裂和瘘', 'K60:ZLXCZ', '裂和瘘治疗性操作组', 4922.00, '', 0, 17, '2026-10-18'),
  ('dip-k60-019', 'K60', '肛门及直肠区的裂和瘘', 'K60:49.1200_49.7301', '肛门瘘管切除术_肛瘘挂线术', 4771.00, '复杂性肛瘘', 20, 18, '2026-10-18'),

  ('dip-k61-010', 'K61', '肛门和直肠区脓肿', 'K61:49.0400x008', '肛周脓肿根治术', 4524.00, '肛周脓肿,肛痈', 10, 21, '2026-10-18'),
  ('dip-k61-011', 'K61', '肛门和直肠区脓肿', 'K61:49.0400x008_49.4500', '肛周脓肿根治术_痔结扎术', 6130.00, '', 0, 22, '2026-10-18'),
  ('dip-k61-012', 'K61', '肛门和直肠区脓肿', 'K61:49.1200', '肛门瘘管切除术（脓肿）', 4822.00, '', 0, 23, '2026-10-18'),
  ('dip-k61-020', 'K61', '肛门和直肠区脓肿', 'K61:48.8101', '直肠周围脓肿切开引流术', 4776.00, '', 0, 24, '2026-10-18'),
  ('dip-k61-zlxcz', 'K61', '肛门和直肠区脓肿', 'K61:ZLXCZ', '脓肿治疗性操作组', 5753.00, '', 0, 25, '2026-10-18'),
  ('dip-k61-ss', 'K61', '肛门和直肠区脓肿', 'K61:SS', '脓肿相关手术组', 4934.00, '', 0, 26, '2026-10-18'),

  ('dip-k62-001', 'K62', '肛门和直肠的其他疾病', 'K62:49.3906_49.4500', '肛乳头切除术_痔结扎术', 5948.00, '肛乳头肥大', 10, 31, '2026-10-18'),
  ('dip-k62-002', 'K62', '肛门和直肠的其他疾病', 'K62:49.3906_49.5903', '肛乳头切除术_肛门括约肌切开术', 6726.00, '', 0, 32, '2026-10-18'),

  ('dip-l05-001', 'L05', '藏毛囊肿', 'L05:86.2101', '藏毛囊肿切除术', 4609.00, '藏毛囊肿', 10, 41, '2026-10-18');
