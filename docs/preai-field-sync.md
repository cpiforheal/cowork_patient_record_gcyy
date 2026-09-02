# 前置 AI 阶段字段三层同步维护

阶段表单字段由三层共同定义。增删字段必须三层同步，否则会出现“前端可填但后端丢弃”或“后端保存但导出缺行”。

## 同步点

1. 前端表单：`coshare_patientrecord_sys_frontend/Geeker-Admin/src/views/preAi/encounters/fieldConfig.ts` 的 `preAiStages[*].fields`。
2. 后端写入白名单：`PreAiEncounterService.ALLOWED_FIELDS` 中对应阶段的字段集合。
3. 后端导出层：`PreAiPrivacyService.STAGE_FIELDS` 字段白名单，以及 `buildDocumentView` 中对应章节的 `addNodeRows` / `addViewRow` 行序。

## 配套检查

- `PreAiEncounterService.validateStage`：同步新增/删除必填校验，避免已删字段继续阻断完成。
- `PreAiPrivacyService.FIELD_LABELS`：新增导出字段必须有中文标签。
- `index.vue.generatedTemplateText` 与 `templateTextGenerator.ts`：删掉前端字段后清理不再被调用的模板生成分支；生成函数可先保留，确认无引用后再删除。
- 自动带出逻辑只填空值，并避开 hydration 静默期，避免覆盖服务端已保存内容。

## 手术登记优化约定

手术登记卡对齐手术记录单的结构化要素：保留术式、日期时间、部位、麻醉、诊断、人员、标本/出血/引流/敷料、并发症和术后去向；删除“术中所见”“实际实施步骤”“术后交接”的长文本叙述及模板确认链。

术前诊断可由医生岗主诊断回填，术后诊断默认回填术前诊断；手术者与责任护士可由职责分配回填。以上字段均可人工覆盖。
