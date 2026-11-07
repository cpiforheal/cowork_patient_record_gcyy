-- 医保合规与DIP管理考核：40题，每题2.5分，全部不定项选择
-- 题目/答案/解析由《医保合规与DIP管理考核试卷（40题100分）.docx》离线解析生成
-- 本轮题库只读；后续如需换题另行导入/迁移

CREATE TABLE IF NOT EXISTS clinic_exam_paper (
  id VARCHAR(64) PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(1000) NOT NULL DEFAULT '',
  question_count INT NOT NULL DEFAULT 0,
  points_per_question DECIMAL(5,2) NOT NULL DEFAULT 0,
  total_score DECIMAL(5,1) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'CLOSED',
  updated_by VARCHAR(100) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_exam_paper_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_exam_question (
  id VARCHAR(64) PRIMARY KEY,
  paper_id VARCHAR(64) NOT NULL,
  seq INT NOT NULL,
  stem VARCHAR(1000) NOT NULL,
  options_json JSON NOT NULL,
  answer_json JSON NOT NULL,
  explanation VARCHAR(1000) NOT NULL DEFAULT '',
  score DECIMAL(5,2) NOT NULL DEFAULT 2.50,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_exam_question_seq (paper_id, seq),
  KEY idx_exam_question_paper (paper_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_exam_attempt (
  id VARCHAR(64) PRIMARY KEY,
  paper_id VARCHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  name_key VARCHAR(100) NOT NULL,
  department VARCHAR(100) NOT NULL,
  active_key VARCHAR(255) NULL,
  status VARCHAR(20) NOT NULL,
  attempt_token VARCHAR(64) NOT NULL,
  answers_json JSON NULL,
  score DECIMAL(5,1) NOT NULL DEFAULT 0,
  correct_count INT NOT NULL DEFAULT 0,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_saved_at DATETIME(3) NULL,
  submitted_at DATETIME(3) NULL,
  duration_seconds INT NOT NULL DEFAULT 0,
  voided_by VARCHAR(100) NULL,
  voided_at DATETIME(3) NULL,
  void_reason VARCHAR(500) NULL,
  reattempt_of VARCHAR(64) NULL,
  UNIQUE KEY uk_exam_attempt_token (attempt_token),
  UNIQUE KEY uk_exam_attempt_active (active_key),
  KEY idx_exam_attempt_status (paper_id, status, submitted_at),
  KEY idx_exam_attempt_department (department, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS clinic_exam_roster (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  name_key VARCHAR(100) NOT NULL,
  department VARCHAR(100) NOT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  account_id VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_exam_roster_person (name_key, department),
  KEY idx_exam_roster_department (department)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO clinic_exam_paper
  (id, code, title, description, question_count, points_per_question, total_score, status, updated_by)
VALUES
  ('exam-medical-insurance-2026', 'MEDICAL_INSURANCE_2026', '医保合规与DIP管理考核（40题100分）',
   '全院医保合规与DIP管理培训考核；40题不定项选择，每题2.5分，满分100分。', 40, 2.50, 100.0, 'CLOSED', 'system')
ON DUPLICATE KEY UPDATE title = VALUES(title), description = VALUES(description),
  question_count = VALUES(question_count), points_per_question = VALUES(points_per_question),
  total_score = VALUES(total_score);

INSERT INTO clinic_exam_question
  (id, paper_id, seq, stem, options_json, answer_json, explanation, score, status)
VALUES
  ('q001','exam-medical-insurance-2026',1,'患者办理入院后，下列哪些事项应立即落实（　）',JSON_ARRAY('核对参保身份，核验医保凭证','初步判定拟入组DIP病种，预判费用框架','告知患者DIP付费规则及自费耗材、项目知情','未行任何评估即开具全套理疗、换药长期医嘱'),JSON_ARRAY('A','B','C'),'入院首日须完成身份核验、病种预判与自费知情，不得未评估批量开医嘱。',2.50,'ACTIVE'),
  ('q002','exam-medical-insurance-2026',2,'混合痔标准DIP路径，本院可设置的住院周期档位有（　）',JSON_ARRAY('3天','5天','7天','10天'),JSON_ARRAY('A','B','C','D'),'本院肛肠路径为混合痔设置3/5/7/10天四档住院周期。',2.50,'ACTIVE'),
  ('q003','exam-medical-insurance-2026',3,'下列属于医保认定的违规收费行为有（　）',JSON_ARRAY('重复收取换药费，医嘱无对应执行记录','分解项目收费，一项操作拆成多条收费','超标准收取中医外治项目费用','诊疗真实，医嘱、执行记录、收费三者一致'),JSON_ARRAY('A','B','C'),'前三项属条例明令禁止的违规收费；三者一致的规范诊疗不属于违规。',2.50,'ACTIVE'),
  ('q004','exam-medical-insurance-2026',4,'非集采内痔套扎器的合规管理要求包括（　）',JSON_ARRAY('术前签署自费耗材知情同意书','在病程、病案中记录使用必要性','严禁将非集采耗材串换为集采耗材上传','不需告知，直接纳入医保统筹报销'),JSON_ARRAY('A','B','C'),'非集采套扎器须知情同意、病程留痕，严禁串码；不得默认纳入统筹。',2.50,'ACTIVE'),
  ('q005','exam-medical-insurance-2026',5,'医嘱上传闭环管理方案中，需同步核查的项目有（　）',JSON_ARRAY('换药、创面处理','各类中医诊疗外治项目','手术、麻醉相关项目','无痛电子肠镜及镜下操作'),JSON_ARRAY('A','B','C','D'),'闭环覆盖换药、中医外治、手术麻醉、肠镜及镜下操作。',2.50,'ACTIVE'),
  ('q006','exam-medical-insurance-2026',6,'肛肠科预防性使用抗生素，符合规范的是（　）',JSON_ARRAY('根据局部情况、基础病及指南确定疗程','任何人不得随意承诺延长输液、追加抗生素','无感染征象不得因患者诉求重启抗生素输注','患者强烈要求即可开具长期抗生素输液医嘱'),JSON_ARRAY('A','B','C'),'预防性抗生素疗程须有依据，任何人不得随意承诺延长或追加。',2.50,'ACTIVE'),
  ('q007','exam-medical-insurance-2026',7,'肛周脓肿仅行切开引流、置管对穿引流，不做根治术时，书写与收费应（　）',JSON_ARRAY('主手术选择切开引流，不得串换为根治术','如实记录脓腔大小、脓液量、引流方式','为提高分值添加无依据的附加手术','耗材、操作与术中记录一一对应'),JSON_ARRAY('A','B','D'),'切开引流不得串换根治术；虚构附加手术属违规抬高分值。',2.50,'ACTIVE'),
  ('q008','exam-medical-insurance-2026',8,'当前飞行检查由“查收费”转向“查诊疗行为”，重点核查内容包括（　）',JSON_ARRAY('是否存在过度诊疗、不必要检查','中医外治项目是否有适应症与评估记录','是否存在诱导住院、挂床、分解住院','病历、医嘱、收费、执行记录是否逻辑匹配'),JSON_ARRAY('A','B','C','D'),'飞检已由单纯查账转向诊疗必要性、病历逻辑与适应症全链条核查。',2.50,'ACTIVE'),
  ('q009','exam-medical-insurance-2026',9,'医嘱闭环管控中发现漏传换药、外治项目，正确处置流程是（　）',JSON_ARRAY('建立缺陷台账登记','及时补录并补齐病程、执行记录','定期汇总通报，纳入岗位绩效考核','私下补收费，不留任何文书记录'),JSON_ARRAY('A','B','C'),'漏费须台账登记、及时补录、纳入考核，不得私下补收而不留痕。',2.50,'ACTIVE'),
  ('q010','exam-medical-insurance-2026',10,'关于乙类医保耗材，河南信阳职工医保规则正确的是（　）',JSON_ARRAY('乙类耗材先行自付一定比例后再进入统筹报销','上传编码、名称必须与实际使用一致','可随意替换耗材编码以方便报销','非集采耗材应单独做好知情告知'),JSON_ARRAY('A','B','D'),'乙类耗材先自付后统筹，编码名称须真实，非集采须知情告知。',2.50,'ACTIVE'),
  ('q011','exam-medical-insurance-2026',11,'DIP结算清单质控中，肛肠病例主要诊断选择原则是（　）',JSON_ARRAY('以本次住院主要治疗的疾病为主要诊断','入院目的为混合痔手术的，主诊断选混合痔','合并高血压、糖尿病作为次要诊断如实填报','为提高分值随意调换主次诊断'),JSON_ARRAY('A','B','C'),'主诊断按主要治疗疾病与入院目的确定，合并症如实填报；不得调换套分。',2.50,'ACTIVE'),
  ('q012','exam-medical-insurance-2026',12,'术后中医外治套餐（耳穴压豆、中药塌渍、穴位悬灸、仪器熏洗）的合规要求有（　）',JSON_ARRAY('每项外治均有医嘱、执行时间、操作者签字','根据病情动态调整，术后逐步递减停用','所有患者统一全套开至出院，不作评估','病程记录外治目的与疗效评估'),JSON_ARRAY('A','B','D'),'外治须医嘱、执行、签字齐全并记录目的与疗效，动态评估递减。',2.50,'ACTIVE'),
  ('q013','exam-medical-insurance-2026',13,'职工医保门诊统筹，出院后继续换药、局部理疗，本院合规要求是（　）',JSON_ARRAY('按政策规定开具治疗项目并做好门诊病历记录','禁止一次性打包全部费用、一次性上传计费','医嘱、执行、收费时序必须匹配','为方便患者将多日项目打包一次报销'),JSON_ARRAY('A','B','C'),'门诊统筹延续治疗须时序匹配、不得一次性打包全部费用上传。',2.50,'ACTIVE'),
  ('q014','exam-medical-insurance-2026',14,'下列情形易造成DIP病例偏差、触发医保预警的有（　）',JSON_ARRAY('费用远超本病种路径上限，无并发症、合并症支撑','费用明显偏低，存在应记未记项目','诊断与手术操作编码错配','严格按标准路径执行，合并症有依据'),JSON_ARRAY('A','B','C'),'费用显著偏高或偏低、编码错配均为DIP大数据预警重点；规范路径且有依据者除外。',2.50,'ACTIVE'),
  ('q015','exam-medical-insurance-2026',15,'《医疗保障基金使用监督管理条例》规定，定点机构禁止的行为有（　）',JSON_ARRAY('虚构医药服务项目','伪造、篡改病历及收费资料','串换药品、耗材、诊疗项目','按规范提供合理必要的医疗服务'),JSON_ARRAY('A','B','C'),'虚构服务、伪造资料、串换项目为条例禁止行为；规范诊疗属应尽义务。',2.50,'ACTIVE'),
  ('q016','exam-medical-insurance-2026',16,'无痛电子肠镜纳入住院DIP路径的合规要求有（　）',JSON_ARRAY('有明确适应症，术前评估与镜下记录完整','仅作术前常规筛查的，须在病程说明必要性','无指征重复开具肠镜并收费','镜下所见、病理回报与收费项目相互对应'),JSON_ARRAY('A','B','D'),'肠镜须有指征并记录完整，禁止无指征重复开具收费。',2.50,'ACTIVE'),
  ('q017','exam-medical-insurance-2026',17,'肛肠住院患者拒绝口服中药方剂时，正确文书处理是（　）',JSON_ARRAY('在病程记录中记载患者拒绝口服中药','保留其余符合适应症的中医外治项目','强行开具口服中药并收费','留存知情沟通记录'),JSON_ARRAY('A','B','D'),'患者拒绝口服中药应记录并留痕，可保留其余有适应症的外治项目，不得强行开药收费。',2.50,'ACTIVE'),
  ('q018','exam-medical-insurance-2026',18,'关于DIP路径“总费用不变、分周期调整项目组合”，说法正确的是（　）',JSON_ARRAY('在总费用框架内按3/5/7/10天周期调整项目排布','中医外治按康复节奏递减，符合临床规律','为控费刻意删减必要治疗、损害医疗质量','路径调整须有临床合理性，不能单纯为凑费用'),JSON_ARRAY('A','B','D'),'路径调整须在总费用框架内并符合临床规律，不得为控费牺牲医疗质量。',2.50,'ACTIVE'),
  ('q019','exam-medical-insurance-2026',19,'骶管麻醉用于肛肠手术，医保与病历合规要点包括（　）',JSON_ARRAY('麻醉记录完整，载明方式、用药、监护','收费项目与实际实施的麻醉方式匹配','实际局麻却上传骶管麻醉收费','术前评估基础病，把控适应症'),JSON_ARRAY('A','B','D'),'麻醉记录与收费须与实际实施的麻醉方式一致，实做与上传不得错位。',2.50,'ACTIVE'),
  ('q020','exam-medical-insurance-2026',20,'下列情况应启动DIP特例单议评估流程的有（　）',JSON_ARRAY('出现严重术后并发症，费用显著超出常规区间','合并多种严重基础病，诊疗大幅偏离标准路径','单纯为规避控费，无依据申请特例单议','合并脓毒血症、糖尿病酮症等严重合并症'),JSON_ARRAY('A','B','D'),'特例单议须有严重并发症或合并症等客观支撑，不得用于规避控费。',2.50,'ACTIVE'),
  ('q021','exam-medical-insurance-2026',21,'本院医嘱上传闭环管理的核查节点包括（　）',JSON_ARRAY('操作前：医嘱开立审核','操作中：执行登记','操作后：收费核对、每日对账','出院前：全病例总复核'),JSON_ARRAY('A','B','C','D'),'闭环设开立审核、执行登记、收费对账、出院复核四道关卡。',2.50,'ACTIVE'),
  ('q022','exam-medical-insurance-2026',22,'关于肛周脓肿、肛瘘的DIP分组，说法正确的是（　）',JSON_ARRAY('单纯切开引流与一次性根治术属不同操作分组','高位复杂肛瘘挂线治疗有独立操作编码','可将切开引流上报为根治手术以提高分值','脓腔范围、括约肌损伤情况须在手术记录如实书写'),JSON_ARRAY('A','B'),'引流、根治、挂线属不同操作分组与编码，不得混用抬高分值。',2.50,'ACTIVE'),
  ('q023','exam-medical-insurance-2026',23,'糖尿病合并肛裂、混合痔围手术期，合规做法有（　）',JSON_ARRAY('血糖异常、尿酮阳性优先内分泌评估并调整方案','白细胞升高应区分感染与应激，不盲目升级抗生素','病程记录血糖监测与降糖方案调整全过程','无视酮症风险，直接安排择期手术'),JSON_ARRAY('A','B','C'),'酮症、高血糖为择期手术重要风险，须先评估纠正再安排手术。',2.50,'ACTIVE'),
  ('q024','exam-medical-insurance-2026',24,'针对患者要求“多输抗生素”，院内统一处置规范是（　）',JSON_ARRAY('耐心告知预防性抗生素时限及指南依据','不随意承诺、不私下重启已停抗生素医嘱','患者坚持时做好沟通记录，必要时请医务部门介入','为防投诉优先满足患者诉求'),JSON_ARRAY('A','B','C'),'应统一口径、留痕沟通，不得为防投诉而突破合规边界。',2.50,'ACTIVE'),
  ('q025','exam-medical-insurance-2026',25,'中医肛肠口服、坐浴自拟方剂的医保合规要求有（　）',JSON_ARRAY('辨证记录完整，四诊、治法与方药理法一致','须由具备中医执业资质的医师开具','可不辨证，批量开具相同方药','中药饮片、坐浴制剂收费与医嘱一致'),JSON_ARRAY('A','B'),'中药须辨证开具、资质合规、理法方药一致、收费与医嘱相符。',2.50,'ACTIVE'),
  ('q026','exam-medical-insurance-2026',26,'下列属于“串换项目”违规的有（　）',JSON_ARRAY('将普通换药上报为特殊创面修复','将普通熏洗上报为高价专项康复项目','实际使用普通耗材，上传高值耗材编码','项目、操作与收费完全匹配'),JSON_ARRAY('A','B','C'),'前三项均属典型串换项目行为；项目操作收费一致的为合规。',2.50,'ACTIVE'),
  ('q027','exam-medical-insurance-2026',27,'入院DIP流程中，出院前质控复核的重点有（　）',JSON_ARRAY('结算清单诊断、手术编码复核','医嘱、执行、收费、病历一致性核查','自费项目与耗材知情核查','不做复核，直接提交结算'),JSON_ARRAY('A','B','C'),'出院前须完成结算清单、医嘱收费、知情同意复核后方可结算。',2.50,'ACTIVE'),
  ('q028','exam-medical-insurance-2026',28,'混合痔横分纵扎术的病历与收费合规要求有（　）',JSON_ARRAY('手术记录描述术式与结扎点位','收费项目匹配术式编码，不叠加无关手术','将普通外剥内扎串换为横分纵扎收费','术后创面处理、换药按时记录'),JSON_ARRAY('A','B','D'),'术式编码与收费须与手术记录一致，禁止串换术式收费。',2.50,'ACTIVE'),
  ('q029','exam-medical-insurance-2026',29,'飞行检查转向“查诊疗行为”，其含义包括（　）',JSON_ARRAY('更看重诊疗必要性、适应症','病历描述、评估记录、随访记录权重提升','只要收费金额无误，病历可随意书写','无适应症过度开展理疗更易被预警'),JSON_ARRAY('A','B','D'),'收费无误不能替代病历质量，无适应症理疗属重点预警对象。',2.50,'ACTIVE'),
  ('q030','exam-medical-insurance-2026',30,'本院不允许随意打包一次性上传多日治疗费用，原因有（　）',JSON_ARRAY('存在重复计费、提前计费的审核风险','治疗实际未发生即先行收费，涉嫌违规','患者中途出院易产生退费与纠纷','打包上传可简化工作，完全合规'),JSON_ARRAY('A','B','C'),'提前打包计费存在重复计费、未做先收及退费纠纷风险。',2.50,'ACTIVE'),
  ('q031','exam-medical-insurance-2026',31,'关于挂床住院的认定与管控，说法正确的是（　）',JSON_ARRAY('无住院指征、日间短暂来院即办住院属挂床','每日须有查房、评估及必要诊疗记录','患者长期不在院仍持续收取床位、理疗费用属违规','办理入院手续后无论是否在院均可正常计费'),JSON_ARRAY('A','B','C'),'挂床住院属重点打击行为，无诊疗、不在院不得计费。',2.50,'ACTIVE'),
  ('q032','exam-medical-insurance-2026',32,'DIP付费下并发症、合并症填报，正确的是（　）',JSON_ARRAY('真实存在的高血压、糖尿病、冠心病应如实填报','合并症须有对应检查、用药、病程支撑','虚构合并症人为抬高分值属骗保风险','合并症即使存在也一律不填报'),JSON_ARRAY('A','B','C'),'合并症须真实并有诊疗支撑，虚构抬高分值涉嫌骗保。',2.50,'ACTIVE'),
  ('q033','exam-medical-insurance-2026',33,'耳穴压豆、中药塌渍等中医外治项目医保拒付高发原因有（　）',JSON_ARRAY('无医嘱、无执行时间','无适应症，批量普适开展','收费频次超出规范上限','医嘱、执行、收费三者时序统一'),JSON_ARRAY('A','B','C'),'无医嘱、无指征、超频次为中医外治拒付三大主因。',2.50,'ACTIVE'),
  ('q034','exam-medical-insurance-2026',34,'病案编码质控中，肛肠病例的易错点有（　）',JSON_ARRAY('主要手术与实际术式不符','肛乳头肥大、肛裂、混合痔诊断漏填或错填','引流术与根治术编码混淆','编码与手术记录、术后诊断一致'),JSON_ARRAY('A','B','C'),'主手术与术式不符、诊断漏填错填、编码混淆为肛肠病案常见缺陷。',2.50,'ACTIVE'),
  ('q035','exam-medical-insurance-2026',35,'医保智能监控发出病例预警后，本院第一时间应（　）',JSON_ARRAY('病案、管床医师、收费联合复盘','核查病历、医嘱、收费，区分合理偏差与违规','确属违规及时整改并退回违规基金','忽略预警，不作核查'),JSON_ARRAY('A','B','C'),'预警须联合复盘、核查区分、整改退费，不得忽略。',2.50,'ACTIVE'),
  ('q036','exam-medical-insurance-2026',36,'关于本院医嘱闭环缺陷考核机制，正确的是（　）',JSON_ARRAY('漏传换药、理疗、手术项目纳入岗位绩效考核','定期组织全员通报缺陷案例','仅处罚收费人员，开单医师不承担责任','建立自查清单，每日岗位自查'),JSON_ARRAY('A','B','D'),'闭环责任由开单、执行、收费多方共担，非仅收费人员。',2.50,'ACTIVE'),
  ('q037','exam-medical-insurance-2026',37,'职工医保住院患者自费项目管理要求有（　）',JSON_ARRAY('自费耗材、自费治疗必须签署知情同意','知情同意书留存病案','口头告知即可，不需书面记录','自费项目费用单独清晰列明并告知患者'),JSON_ARRAY('A','B','D'),'自费项目须书面知情并留存病案，费用单独列明告知。',2.50,'ACTIVE'),
  ('q038','exam-medical-insurance-2026',38,'下列情形不适合强行纳入标准混合痔DIP路径的有（　）',JSON_ARRAY('合并高位复杂肛瘘，需分次手术','肛周广泛脓肿、脓腔巨大，需长期引流','单纯混合痔，无严重基础病','术后大出血，需二次手术止血'),JSON_ARRAY('A','B','D'),'复杂肛瘘、巨大脓肿、二次手术不宜强行纳入标准路径。',2.50,'ACTIVE'),
  ('q039','exam-medical-insurance-2026',39,'DIP路径中医项目递减方案的设计原则有（　）',JSON_ARRAY('术后早期外治项目齐全，随创面好转逐步减少','停用外治项目时在病程记录评估理由','从入院到出院全部项目一成不变','根据创面、疼痛、排便情况个体化调整'),JSON_ARRAY('A','B','D'),'外治须随创面恢复递减并记录理由，个体化调整。',2.50,'ACTIVE'),
  ('q040','exam-medical-insurance-2026',40,'结合本院肛肠专科，全员医保合规培训的目标包括（　）',JSON_ARRAY('熟悉DIP入组、路径与结算清单质控要点','掌握医嘱—执行—收费闭环，减少漏费错费','熟悉肛肠科高频违规点与飞检核查方向','知晓预防性抗生素、耗材、中医项目的合规边界'),JSON_ARRAY('A','B','C','D'),'四项均为本院肛肠专科医保合规培训核心目标。',2.50,'ACTIVE')
ON DUPLICATE KEY UPDATE stem = VALUES(stem), options_json = VALUES(options_json),
  answer_json = VALUES(answer_json), explanation = VALUES(explanation), score = VALUES(score),
  status = VALUES(status);
