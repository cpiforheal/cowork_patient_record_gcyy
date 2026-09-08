# -*- coding: utf-8 -*-
"""FlipCard acceptance fixes: $el ref, :deep face styles, back-face overflow caps."""
import io

p = r"E:\新建文件夹\hos_cowork\cowork_patient_record_gcyy-main\cowork_patient_record_gcyy\coshare_patientrecord_sys_frontend\Geeker-Admin\src\views\preAi\encounters\index.vue"
t = io.open(p, encoding="utf-8", newline="").read()

# 1. setRef: unwrap component instance (FlipCard ref returns component, not DOM element)
old_ref = """const setPatientArchiveMasonryCardRef = (element: Element | null, item: PreAiPatientCase) => {
  const existing = patientArchiveCardElements.get(item.id);
  if (existing) patientArchiveObserver?.unobserve(existing);
  if (!element) {
    patientArchiveCardElements.delete(item.id);
    return;
  }
  patientArchiveCardElements.set(item.id, element);
  observePatientArchiveCard(element, item);
};"""
new_ref = """const setPatientArchiveMasonryCardRef = (
  element: Element | ComponentPublicInstance | null,
  item: PreAiPatientCase
) => {
  // FlipCard 是组件：ref 拿到的是组件实例，需解包 $el 才能交给 IntersectionObserver
  const el =
    element && "$el" in (element as Record<string, unknown>) ? ((element as ComponentPublicInstance).$el as Element) : (element as Element | null);
  const existing = patientArchiveCardElements.get(item.id);
  if (existing) patientArchiveObserver?.unobserve(existing);
  if (!el) {
    patientArchiveCardElements.delete(item.id);
    return;
  }
  patientArchiveCardElements.set(item.id, el);
  observePatientArchiveCard(el, item);
};"""
assert old_ref in t, "ref anchor missing"
t = t.replace(old_ref, new_ref, 1)

# 1b. ComponentPublicInstance type import
old_vue = 'import { computed, onMounted, ref, watch, nextTick } from "vue";'
if old_vue not in t:
    import re
    m = re.search(r'import \{([^}]+)\} from "vue";', t)
    assert m, "vue import missing"
    names = m.group(1)
    t = t.replace(m.group(0), f'import {{ {names.strip()}, type ComponentPublicInstance }} from "vue";', 1)
else:
    t = t.replace(old_vue, 'import { computed, onMounted, ref, watch, nextTick, type ComponentPublicInstance } from "vue";', 1)
print("ref unwrap done")

# 2. face styles need :deep (scoped styles cannot pierce child component internals)
old_face = """.patient-flip-card .flip-card-face {
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid transparent;
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.patient-flip-card:hover .flip-card-face,
.patient-flip-card.active .flip-card-face {
  border-color: var(--el-color-primary-light-3);
  box-shadow: 0 14px 30px rgb(0 150 136 / 15%);
}
.patient-flip-card.active .flip-card-face {
  border-left-color: var(--el-color-primary);
}"""
new_face = """.patient-flip-card :deep(.flip-card-face) {
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid transparent;
  border-radius: 16px;
  background: var(--el-bg-color);
  box-shadow: 0 10px 24px rgb(15 23 42 / 6%);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}
.patient-flip-card:hover :deep(.flip-card-face),
.patient-flip-card.active :deep(.flip-card-face) {
  border-color: var(--el-color-primary-light-3);
  box-shadow: 0 14px 30px rgb(0 150 136 / 15%);
}
.patient-flip-card.active :deep(.flip-card-face) {
  border-left-color: var(--el-color-primary);
}"""
assert old_face in t, "face anchor missing"
t = t.replace(old_face, new_face, 1)

# 3. back-face overflow cap + gallery preview sizing
old_back = """.patient-flip-back {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 10px;
  height: 100%;
  padding: 14px 16px;
  background: color-mix(in srgb, var(--el-color-primary) 4%, var(--el-bg-color));
}"""
new_back = """.patient-flip-back {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 10px;
  height: 100%;
  padding: 14px 16px;
  overflow: hidden;
  border-radius: inherit;
  background: color-mix(in srgb, var(--el-color-primary) 4%, var(--el-bg-color));
}
// 图片仅供预览：小尺寸缩略图，点击经组件内查看器放大
.patient-flip-back :deep(.attachment-gallery .attachment-card) {
  width: 104px;
}
.patient-flip-back :deep(.attachment-gallery .image-thumbnail) {
  height: 72px;
}"""
assert old_back in t, "back anchor missing"
t = t.replace(old_back, new_back, 1)
io.open(p, "w", encoding="utf-8", newline="").write(t)
print("ALL_PATCHED")
