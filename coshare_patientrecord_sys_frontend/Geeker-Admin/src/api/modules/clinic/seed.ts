import { recordSections, roleLabel, type RecordField, type UserRole } from "@/config/fieldPermissions";
import type { AccountRow, AuditLogRow, ClinicDb, DepartmentRow, DictRow, RoleRow, TemplateFieldRule } from "./types";

export const roleToDepartment: Record<UserRole, string> = {
  admin: "信息/院办",
  frontdesk: "前台",
  lab: "化验室",
  ecg: "心电室",
  ultrasound: "B超/放射",
  doctor: "门诊",
  nurse: "治疗室",
  quality: "质控/病案"
};

const seedDepartments = (): DepartmentRow[] => [
  {
    id: "frontdesk",
    name: "前台",
    uploadTypes: "复查照片、身份资料、患者基础信息",
    scope: "当天门诊患者基础资料",
    defaultRole: "frontdesk"
  },
  { id: "lab", name: "化验室", uploadTypes: "血常规、尿常规、凝血、生化、术前八项", scope: "本科室检查资料", defaultRole: "lab" },
  { id: "ecg", name: "心电室", uploadTypes: "心电图", scope: "本科室检查资料", defaultRole: "ecg" },
  {
    id: "ultrasound",
    name: "B超/放射",
    uploadTypes: "B超、放射影像、肠镜附件",
    scope: "本科室检查资料",
    defaultRole: "ultrasound"
  },
  {
    id: "doctor",
    name: "门诊",
    uploadTypes: "病史、诊断、治疗方案、手术方案",
    scope: "完整病历查看与医生字段维护",
    defaultRole: "doctor"
  },
  {
    id: "nurse",
    name: "治疗室",
    uploadTypes: "生命体征、治疗室配合记录、护理相关资料",
    scope: "治疗室与护理相关字段维护",
    defaultRole: "nurse"
  },
  { id: "quality", name: "质控/病案", uploadTypes: "DIP、归档、质控意见", scope: "质控审核与归档复核", defaultRole: "quality" }
];

const seedRoles = (): RoleRow[] => [
  {
    id: "frontdesk",
    name: "前台",
    role: "frontdesk",
    members: 0,
    desc: "创建患者、维护基础信息、上传前台采集资料。",
    permissions: ["patient:create", "patient:read", "patient:update", "document:upload"],
    editableSections: ["basic"]
  },
  {
    id: "lab",
    name: "化验室",
    role: "lab",
    members: 0,
    desc: "上传和维护本科室检验报告字段。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["auxiliary"]
  },
  {
    id: "ecg",
    name: "心电室",
    role: "ecg",
    members: 0,
    desc: "上传和维护心电图检查资料。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["auxiliary"]
  },
  {
    id: "ultrasound",
    name: "B超/放射",
    role: "ultrasound",
    members: 0,
    desc: "上传和维护影像、B超、肠镜等检查资料。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["auxiliary"]
  },
  {
    id: "doctor",
    name: "医生",
    role: "doctor",
    members: 0,
    desc: "补充病史、诊断、专科检查和手术方案。",
    permissions: ["patient:read", "field:edit", "document:download"],
    editableSections: ["chiefComplaint", "presentIllness", "history", "specialExam", "mainDiagnosis", "operation"]
  },
  {
    id: "nurse",
    name: "护士/治疗室",
    role: "nurse",
    members: 0,
    desc: "维护生命体征、治疗室配合记录和护理相关字段。",
    permissions: ["patient:read", "document:upload", "field:edit"],
    editableSections: ["specialExam", "auxiliary", "operation"]
  },
  {
    id: "quality",
    name: "质控",
    role: "quality",
    members: 0,
    desc: "质控审核、DIP分组、归档复核。",
    permissions: ["patient:read", "audit:read", "document:restore", "role:grant"],
    editableSections: ["dip", "documentScope", "qualityCheck"]
  },
  {
    id: "admin",
    name: "管理员",
    role: "admin",
    members: 1,
    desc: "系统设置、账号、角色、权限、审计。",
    permissions: ["*"],
    editableSections: recordSections.map(section => section.key)
  }
];

const seedAccounts = (): AccountRow[] => [
  {
    id: "admin",
    username: "admin",
    password: "Init@Coshare2026!",
    name: roleLabel("admin"),
    department: roleToDepartment.admin,
    role: "admin",
    roleLabel: roleLabel("admin"),
    scope: "系统全局配置",
    status: "启用",
    createdAt: "2026-06-10 08:00:00",
    updatedAt: "2026-06-10 08:00:00"
  }
];

const seedDictionaries = (): DictRow[] => [
  { id: "bloodRoutine", name: "血常规", department: "化验室", naming: "患者姓名-门诊号-血常规-版本", required: "是" },
  { id: "coagulation", name: "凝血功能", department: "化验室", naming: "患者姓名-门诊号-凝血功能-版本", required: "是" },
  { id: "ecg", name: "心电图", department: "心电室", naming: "患者姓名-门诊号-心电图-版本", required: "按需" },
  { id: "ultrasound", name: "B超", department: "B超/放射", naming: "患者姓名-门诊号-B超-版本", required: "按需" },
  { id: "followupPhoto", name: "复查照片", department: "前台", naming: "患者姓名-门诊号-复查照片-序号", required: "按需" }
];

export const seedTemplateFieldRules = (): TemplateFieldRule[] =>
  recordSections.flatMap((section, sectionIndex) =>
    section.fields.map((field: RecordField, fieldIndex) => ({
      id: `${section.key}-${field.key}`,
      sectionKey: section.key,
      sectionTitle: section.title,
      stage: section.stage,
      department: section.department,
      fieldKey: field.key,
      fieldLabel: field.label,
      editors: field.editors,
      editorLabels: field.editors.map(editor => roleLabel(editor)),
      required: Boolean(field.required),
      evidence: field.evidence || "",
      enabled: true,
      printable: true,
      qualityCheck: Boolean(field.required || field.evidence),
      sortNo: sectionIndex * 100 + fieldIndex + 1,
      updatedAt: "2026-06-10 08:00:00"
    }))
  );

const seedAuditLogs = (): AuditLogRow[] => [];

export const initialFieldValues = () =>
  recordSections.reduce<Record<string, string>>((values, section) => {
    section.fields.forEach(field => {
      values[field.key] = field.key === "hospitalName" ? field.value : "";
    });
    return values;
  }, {});

export const createSeedDb = (): ClinicDb => ({
  patients: [],
  records: {},
  archive: {},
  documents: {},
  accounts: seedAccounts(),
  roles: seedRoles(),
  departments: seedDepartments(),
  dictionaries: seedDictionaries(),
  templateFieldRules: seedTemplateFieldRules(),
  auditLogs: seedAuditLogs()
});

export const hydrateDb = (db: ClinicDb) => {
  const legacySeedPatientIds = new Set(["1", "2", "3", "p-001", "p-002"]);
  const legacySeedAuditIds = new Set(["audit-1", "audit-2", "audit-3", "audit-001"]);
  const removedPatientIds = new Set<string>();

  db.patients ??= [];
  db.records ??= {};
  db.archive ??= {};
  db.documents ??= {};
  const existingAccounts = db.accounts ?? seedAccounts();
  db.accounts = existingAccounts;
  const defaultRoles = seedRoles();
  const hasCompleteRoleBaseline = defaultRoles.every(defaultRole => db.roles?.some(role => role.role === defaultRole.role));
  if (!db.roles?.every(role => role.id && role.role && typeof role.members === "number") || !hasCompleteRoleBaseline) {
    const storedRoles = db.roles?.filter(role => role.id && role.role && typeof role.members === "number") ?? [];
    const storedByRole = new Map(storedRoles.map(role => [role.role, role]));
    const defaultRoleValues = new Set(defaultRoles.map(role => role.role));
    db.roles = [
      ...defaultRoles.map(defaultRole => ({
        ...defaultRole,
        ...storedByRole.get(defaultRole.role)
      })),
      ...storedRoles.filter(role => !defaultRoleValues.has(role.role))
    ];
  }
  if (!db.departments?.length) db.departments = seedDepartments();
  if (!db.dictionaries?.length) db.dictionaries = seedDictionaries();
  if (!db.templateFieldRules?.length) db.templateFieldRules = seedTemplateFieldRules();
  db.auditLogs ??= seedAuditLogs();

  db.patients = db.patients.filter(patient => {
    const isLegacySeedPatient = legacySeedPatientIds.has(patient.id);
    if (isLegacySeedPatient) removedPatientIds.add(patient.id);
    return !isLegacySeedPatient;
  });
  removedPatientIds.forEach(patientId => {
    delete db.records[patientId];
    delete db.archive[patientId];
    delete db.documents![patientId];
  });

  const seedAdmin = seedAccounts()[0];
  const storedAdmin = existingAccounts.find(account => account.username === "admin" || account.role === "admin");
  const storedBusinessAccounts = existingAccounts.filter(account => account.username !== "admin" && account.role !== "admin");
  const adminAccount: AccountRow = {
    ...seedAdmin,
    ...storedAdmin,
    id: "admin",
    username: "admin",
    department: roleToDepartment.admin,
    role: "admin",
    roleLabel: roleLabel("admin"),
    scope: "系统全局配置",
    status: "启用"
  };
  if (storedAdmin && !storedAdmin.password) {
    delete adminAccount.password;
  }
  db.accounts = [adminAccount, ...storedBusinessAccounts];
  const roleMemberCounts = db.accounts.reduce<Record<string, number>>((result, account) => {
    result[account.role] = (result[account.role] || 0) + 1;
    return result;
  }, {});
  db.roles = db.roles.map(role => ({
    ...role,
    members: roleMemberCounts[role.role] || 0
  }));
  db.auditLogs = db.auditLogs.filter(log => !legacySeedAuditIds.has(log.id));
  Object.keys(db.documents).forEach(patientId => {
    db.documents![patientId] = db.documents![patientId].map(document => ({
      ...document,
      status: document.status || "active"
    }));
  });
  return db;
};
