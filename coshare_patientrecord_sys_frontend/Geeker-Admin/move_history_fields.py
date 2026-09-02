# -*- coding: utf-8 -*-
import io

# ---------- A. fieldConfig.ts：病史字段块从 REGISTRATION 迁至 INSPECTION 末尾 ----------
FC = r"src/views/preAi/encounters/fieldConfig.ts"
lines = io.open(FC, encoding="utf-8").read().split("\n")

# 定位 allergyHistory 的 `{` 行与 registrationNote 行
start = None
note_idx = None
for i, line in enumerate(lines):
    if start is None and 'key: "allergyHistory",' in line:
        start = i - 1  # 对象的 `{` 行
    if 'key: "registrationNote"' in line:
        note_idx = i
assert start is not None and note_idx is not None and start < note_idx, (start, note_idx)

block = lines[start:note_idx]  # 含 allergyHistory..familyHistory 及各自闭尾
assert any('key: "familyHistory"' in l for l in block), "块内必须有 familyHistory"
assert any('key: "allergyHistory"' in l for l in block)
assert block[-1].strip() == "}," or block[-1].strip() == "}", block[-1]

# 从 REGISTRATION 中移除
rest = lines[:start] + lines[note_idx:]

# 定位 INSPECTION.fields 的收口 `    ]`（RECEPTION 对象前的那个）
recv = None
for i, line in enumerate(rest):
    if 'code: "RECEPTION",' in line:
        recv = i
        break
assert recv is not None
close_idx = recv - 2  # `    ]` 行（recv-1 是 `  },`）
assert rest[close_idx].strip() == "]" , rest[close_idx]
assert rest[close_idx - 1].strip() == "}", rest[close_idx - 1]

# 上一字段补逗号 + 插入块
rest[close_idx - 1] = rest[close_idx - 1] + ","
comment = ["      // 病史采集（自前台登记迁入：检查室随检查同步询问，数据仍归属接诊室草稿）"]
moved = comment + block
new_lines = rest[:close_idx] + moved + rest[close_idx:]

io.open(FC, "w", encoding="utf-8", newline="\n").write("\n".join(new_lines))
print("fieldConfig.ts 迁移完成: 块大小", len(block), "行")

# ---------- B. index.vue：同步函数泛化 + 调用点 + 类绑定 + 表单过滤 ----------
P = r"src/views/preAi/encounters/index.vue"
s = io.open(P, encoding="utf-8").read()

# B1. 同步函数泛化
old_sync = """const syncRegistrationHistoryToReception = () => {
  const registration = stageForms.REGISTRATION;
  const reception = stageForms.RECEPTION;
  stageByCode("REGISTRATION").fields.forEach(field => {
    if (!isHistoryIntakeKey(field.key)) return;
    const value = registration[field.key];"""
new_sync = """const syncRegistrationHistoryToReception = (fromStage: PreAiStageCode) => {
  const registration = stageForms[fromStage];
  const reception = stageForms.RECEPTION;
  stageByCode(fromStage).fields.forEach(field => {
    if (!isHistoryIntakeKey(field.key)) return;
    const value = registration[field.key];"""
assert s.count(old_sync) == 1, "sync fn"
s = s.replace(old_sync, new_sync)

old_persist_head = "const persistReceptionHistoryFromRegistration = async () => {"
assert s.count(old_persist_head) == 1, "persist fn"
s = s.replace(old_persist_head, "const persistReceptionHistoryFromStage = async (fromStage: PreAiStageCode) => {")
old_persist_call = "  syncRegistrationHistoryToReception();"
assert s.count(old_persist_call) == 1, "sync call"
s = s.replace(old_persist_call, "  syncRegistrationHistoryToReception(fromStage);")
old_fields_iter = '  const hasHistoryValue = stageByCode("REGISTRATION").fields.some(field => {'
assert s.count(old_fields_iter) == 1, "fields iter"
s = s.replace(old_fields_iter, '  const hasHistoryValue = stageByCode(fromStage).fields.some(field => {')

# B2. 调用点：前台或检查室保存/完成均触发（前台已无病史字段，自动空转）
old_call = '    if (selectedStageCode.value === "REGISTRATION") await persistReceptionHistoryFromRegistration();'
n = s.count(old_call)
assert n >= 1, "call sites: %d" % n
s = s.replace(old_call, '    if (["REGISTRATION", "INSPECTION"].includes(selectedStageCode.value))\n      await persistReceptionHistoryFromStage(selectedStageCode.value);')
print("调用点替换:", n)

# B3. 病史高亮类绑定：REGISTRATION → INSPECTION
old_cls = "'history-intake-field': isHistoryIntakeKey(field.key) && selectedStageCode === 'REGISTRATION'"
assert s.count(old_cls) == 1, "class binding"
s = s.replace(old_cls, "'history-intake-field': isHistoryIntakeKey(field.key) && selectedStageCode === 'INSPECTION'")

# B4. el-form 恢复 INSPECTION 可见（限 CURRENT 视图），字段过滤为病史采集
old_form_vif = '                  <el-form v-if="selectedStageCode !== \'INSPECTION\'" label-position="top" class="stage-form">'
assert s.count(old_form_vif) == 1, "el-form v-if"
s = s.replace(old_form_vif, '                  <el-form\n                    v-if="selectedStageCode !== \'INSPECTION\' || inspectionView === \'CURRENT\'"\n                    label-position="top"\n                    class="stage-form"\n                  >')

old_fields_vfor = '                      <el-form-item\n                        v-for="field in visibleStageFields"'
assert s.count(old_fields_vfor) == 1, "v-for"
s = s.replace(old_fields_vfor, '                      <el-form-item\n                        v-for="field in stageFormFields"')

old_comp = """const visibleStageFields = computed(() =>
  selectedStage.value.fields.filter(field => !field.visible || field.visible(stageForms[selectedStageCode.value]))
);"""
assert s.count(old_comp) == 1, "computed"
s = s.replace(old_comp, """const visibleStageFields = computed(() =>
  selectedStage.value.fields.filter(field => !field.visible || field.visible(stageForms[selectedStageCode.value]))
);
// 检查室收束视图：字段表单仅渲染病史采集组（检查所见已由上方模板 textarea 承载）
const stageFormFields = computed(() => {
  const fields = visibleStageFields.value;
  if (selectedStageCode.value !== "INSPECTION") return fields;
  return fields.filter(field => isHistoryIntakeKey(field.key));
});""")

io.open(P, "w", encoding="utf-8", newline="\n").write(s)
print("index.vue 更新完成")
