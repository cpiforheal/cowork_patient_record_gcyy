export type DepartmentCareType = "outpatient" | "inpatient" | "other";

export type DepartmentTemplateLine = {
  sourceRow: number;
  serviceGroup: string;
  careType: DepartmentCareType;
  materialName: string;
  unit: string;
  standardQuantity: number | null;
  defaultVolume: number;
};

export type DepartmentTemplate = {
  key: string;
  department: string;
  monthDays: number;
  lines: DepartmentTemplateLine[];
};

export const departmentConsumptionTemplates: DepartmentTemplate[] = [
  {
    "key": "physiotherapy",
    "department": "理疗室",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "pvc手套（双）",
        "unit": "双",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "卫生纸（节）",
        "unit": "节",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "换药棉花敷料（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 7,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "导尿灌肠管（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 8,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "医用胶带（条）",
        "unit": "条",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 9,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "换药棉球（个）",
        "unit": "个",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 10,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 11,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "酒精消毒（ml）",
        "unit": "ml",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 12,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "换药碘伏棉球（个）",
        "unit": "个",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 13,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "中单（张/天）",
        "unit": "张/天",
        "standardQuantity": 0.333,
        "defaultVolume": 0
      },
      {
        "sourceRow": 14,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "医用胶布（条）",
        "unit": "条",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 15,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "换药卫生纸（张）",
        "unit": "张",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 16,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "导尿管（灌肠管）（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 17,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "一次性白色隔离垫（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 18,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "换药棉花敷料（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 19,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "一次性马桶隔离膜（m）",
        "unit": "m",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 20,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "艾条（个）",
        "unit": "个",
        "standardQuantity": 0.5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 21,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "pvc手套（双）",
        "unit": "双",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 22,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "红霉素（g）",
        "unit": "g",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 23,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "75%消毒酒精（ml）",
        "unit": "ml",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 24,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "碘伏消毒液（ml）",
        "unit": "ml",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 25,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "蓝色小铺巾（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 26,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "白色脱脂纱布块",
        "unit": "",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 27,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "橡胶检查手套（双）",
        "unit": "双",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 28,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "黑色垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 29,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "医疗垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 30,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "laboratory",
    "department": "检验科",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "紫头管（EDTA抗凝管）（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 5,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "蓝头管（枸橼酸钠抗凝管）（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 6,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "采血针（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 7,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 8,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "棉签（根）",
        "unit": "根",
        "standardQuantity": 3,
        "defaultVolume": 10
      },
      {
        "sourceRow": 9,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "止血带（根）",
        "unit": "根",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 10,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "凝血四项试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 11,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "术前八项试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 12,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "血常规试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 13,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "血糖试纸（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 14,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "A5纸（张）",
        "unit": "张",
        "standardQuantity": 4,
        "defaultVolume": 10
      },
      {
        "sourceRow": 15,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "心电图纸（张）",
        "unit": "张",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 16,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "棉球（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 17,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "紫头管（EDTA抗凝管）（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 18,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "蓝头管（枸橼酸钠抗凝管）（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 19,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "绿头管（肝素钠抗凝管）（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 20,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "采血针（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 21,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 22,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "棉签（根）",
        "unit": "根",
        "standardQuantity": 3,
        "defaultVolume": 10
      },
      {
        "sourceRow": 23,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "止血带（根）",
        "unit": "根",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 24,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "凝血四项试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 25,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "术前八项试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 26,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "血常规试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 27,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "生化试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 28,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "电解质试剂（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 29,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "尿试纸条（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 30,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "尿杯（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 31,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "A5纸（张）",
        "unit": "张",
        "standardQuantity": 6,
        "defaultVolume": 10
      },
      {
        "sourceRow": 32,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "心电图纸（张）",
        "unit": "张",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 33,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "棉球（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 10
      },
      {
        "sourceRow": 34,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "PVC手套（双/人/天）",
        "unit": "双/人/天",
        "standardQuantity": 1,
        "defaultVolume": 1
      },
      {
        "sourceRow": 35,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "口罩（个/人/天）",
        "unit": "个/人/天",
        "standardQuantity": 1,
        "defaultVolume": 1
      },
      {
        "sourceRow": 36,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 2,
        "defaultVolume": 1
      },
      {
        "sourceRow": 37,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "医疗垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 1,
        "defaultVolume": 1
      },
      {
        "sourceRow": 38,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "利器盒",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 39,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "小中单（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.5,
        "defaultVolume": 1
      },
      {
        "sourceRow": 40,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "大中单（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.143,
        "defaultVolume": 1
      },
      {
        "sourceRow": 41,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "卫生纸",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 42,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "医用抗菌洗手液",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 43,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "84消毒液",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 44,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "一次性消毒凝胶",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 45,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "生化DC-80清洗液",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 46,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "血常规稀释液（ml）",
        "unit": "ml",
        "standardQuantity": 20000,
        "defaultVolume": 1
      },
      {
        "sourceRow": 47,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "溶血剂（ml）",
        "unit": "ml",
        "standardQuantity": 600,
        "defaultVolume": 1
      },
      {
        "sourceRow": 48,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "探头清洁液（ml）",
        "unit": "ml",
        "standardQuantity": 50,
        "defaultVolume": 1
      },
      {
        "sourceRow": 49,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "生化试剂14项",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 50,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "电解质试剂",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 51,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "C14试剂",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 52,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "CRP试剂",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 53,
        "serviceGroup": "其他耗材",
        "careType": "other",
        "materialName": "糖化血红蛋白试剂",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      }
    ]
  },
  {
    "key": "nursing",
    "department": "护理部",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "止血带（条）",
        "unit": "条",
        "standardQuantity": 1,
        "defaultVolume": 5
      },
      {
        "sourceRow": 5,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "输液贴（片）",
        "unit": "片",
        "standardQuantity": 2,
        "defaultVolume": 5
      },
      {
        "sourceRow": 6,
        "serviceGroup": "门诊患者",
        "careType": "outpatient",
        "materialName": "20ml注射器",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 5
      },
      {
        "sourceRow": 7,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "输液贴（片）",
        "unit": "片",
        "standardQuantity": 2,
        "defaultVolume": 20
      },
      {
        "sourceRow": 8,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "止血带（人份）",
        "unit": "人份",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 9,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "20ml注射器（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 20
      },
      {
        "sourceRow": 10,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 11,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "医疗废物垃圾袋（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 12,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "利器盒",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 20
      },
      {
        "sourceRow": 13,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "84消毒",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 20
      },
      {
        "sourceRow": 14,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "A4纸（张/天）",
        "unit": "张/天",
        "standardQuantity": 20,
        "defaultVolume": 20
      },
      {
        "sourceRow": 15,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "医疗扎带（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 16,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 3,
        "defaultVolume": 20
      },
      {
        "sourceRow": 17,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "棉签（根）",
        "unit": "根",
        "standardQuantity": 2,
        "defaultVolume": 20
      },
      {
        "sourceRow": 18,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "黑色垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 19,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "PVC手套（双/天）",
        "unit": "双/天",
        "standardQuantity": 2,
        "defaultVolume": 20
      },
      {
        "sourceRow": 20,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "薄膜手套（双/天）",
        "unit": "双/天",
        "standardQuantity": 2,
        "defaultVolume": 20
      },
      {
        "sourceRow": 21,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "50ml注射器（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 22,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "腕带（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 23,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "长尾夹（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 24,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "床头卡（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 20
      },
      {
        "sourceRow": 25,
        "serviceGroup": "住院患者",
        "careType": "inpatient",
        "materialName": "固体胶",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 20
      }
    ]
  },
  {
    "key": "tcm",
    "department": "中医科",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "门诊中医套餐",
        "careType": "outpatient",
        "materialName": "手写处方（张）",
        "unit": "张",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "门诊中医套餐",
        "careType": "outpatient",
        "materialName": "机打处方（张）",
        "unit": "张",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "门诊中医套餐",
        "careType": "outpatient",
        "materialName": "签字笔芯",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      },
      {
        "sourceRow": 7,
        "serviceGroup": "门诊中西医结合套餐",
        "careType": "outpatient",
        "materialName": "手写处方（张）",
        "unit": "张",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 8,
        "serviceGroup": "门诊中西医结合套餐",
        "careType": "outpatient",
        "materialName": "机打处方（张）",
        "unit": "张",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 9,
        "serviceGroup": "门诊中西医结合套餐",
        "careType": "outpatient",
        "materialName": "签字笔芯",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      },
      {
        "sourceRow": 10,
        "serviceGroup": "住院中医套餐",
        "careType": "inpatient",
        "materialName": "手写处方（张）",
        "unit": "张",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 11,
        "serviceGroup": "住院中医套餐",
        "careType": "inpatient",
        "materialName": "签字笔芯",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      },
      {
        "sourceRow": 12,
        "serviceGroup": "住院中西医结合套餐",
        "careType": "inpatient",
        "materialName": "手写处方（张）",
        "unit": "张",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 13,
        "serviceGroup": "住院中西医结合套餐",
        "careType": "inpatient",
        "materialName": "签字笔芯",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "operating",
    "department": "手术室",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "碘伏棉球（个）",
        "unit": "个",
        "standardQuantity": 20,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "纱布块（包）",
        "unit": "包",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "卫生纸（节）",
        "unit": "节",
        "standardQuantity": 20,
        "defaultVolume": 0
      },
      {
        "sourceRow": 7,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "小中单（个）",
        "unit": "个",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 8,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "大中单（个/天）",
        "unit": "个/天",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 9,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "75%酒精（ml）",
        "unit": "ml",
        "standardQuantity": 30,
        "defaultVolume": 0
      },
      {
        "sourceRow": 10,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "20毫升注射器（个）",
        "unit": "个",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 11,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "5毫升蓝头注射器（个）",
        "unit": "个",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 12,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "5毫升黄头注射器（个）",
        "unit": "个",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 13,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 70,
        "defaultVolume": 0
      },
      {
        "sourceRow": 14,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "新洁尔棉球（粒）",
        "unit": "粒",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 15,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "医用胶带（m）",
        "unit": "m",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 16,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "透明胶带（m）",
        "unit": "m",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 17,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "黑线（m）",
        "unit": "m",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 18,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "圆针（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 19,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "PVC手套（只）",
        "unit": "只",
        "standardQuantity": 6,
        "defaultVolume": 0
      },
      {
        "sourceRow": 20,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "橡胶手套（只）",
        "unit": "只",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 21,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "口罩（个）",
        "unit": "个",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 22,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 40,
        "defaultVolume": 0
      },
      {
        "sourceRow": 23,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "10毫升过氧化氢",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      },
      {
        "sourceRow": 24,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "刀片（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 25,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "20厘米橡胶管",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      },
      {
        "sourceRow": 26,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "手术衣（件）",
        "unit": "件",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 27,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "帽子（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 28,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "黑色垃圾袋（个）",
        "unit": "个",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 29,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "黄色垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 30,
        "serviceGroup": "有创面手术",
        "careType": "other",
        "materialName": "利器盒（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.071,
        "defaultVolume": 0
      },
      {
        "sourceRow": 31,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "碘伏棉球（粒）",
        "unit": "粒",
        "standardQuantity": 10,
        "defaultVolume": 0
      },
      {
        "sourceRow": 32,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "纱布块（包）",
        "unit": "包",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 33,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "卫生纸（节）",
        "unit": "节",
        "standardQuantity": 15,
        "defaultVolume": 0
      },
      {
        "sourceRow": 34,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "小中单（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 35,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "大中单（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 36,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "75%酒精（ml）",
        "unit": "ml",
        "standardQuantity": 20,
        "defaultVolume": 0
      },
      {
        "sourceRow": 37,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "20毫升注射器（个）",
        "unit": "个",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 38,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "5毫升蓝头注射器（个）",
        "unit": "个",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 39,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "5毫升黄头注射器（个）",
        "unit": "个",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 40,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 30,
        "defaultVolume": 0
      },
      {
        "sourceRow": 41,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "新洁尔棉（粒）",
        "unit": "粒",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 42,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "医用胶带（m）",
        "unit": "m",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 43,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "透明胶带（m）",
        "unit": "m",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 44,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "PVC手套（只）",
        "unit": "只",
        "standardQuantity": 6,
        "defaultVolume": 0
      },
      {
        "sourceRow": 45,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "橡胶手套（只）",
        "unit": "只",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 46,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 47,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 15,
        "defaultVolume": 0
      },
      {
        "sourceRow": 48,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "手术衣（件）",
        "unit": "件",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 49,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "帽子（个/天）",
        "unit": "个/天",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 50,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "黑色垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 51,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "黄色垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 52,
        "serviceGroup": "无创面手术",
        "careType": "other",
        "materialName": "利器盒（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.071,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "anesthesia",
    "department": "麻醉室",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "输液器（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "20毫升耦合剂（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "75%酒精（ml）",
        "unit": "ml",
        "standardQuantity": 30,
        "defaultVolume": 0
      },
      {
        "sourceRow": 7,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "棉签（个）",
        "unit": "个",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 8,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 9,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "止血带（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 10,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "卫生纸（节）",
        "unit": "节",
        "standardQuantity": 20,
        "defaultVolume": 0
      },
      {
        "sourceRow": 11,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "84消毒液（ml/天）",
        "unit": "ml/天",
        "standardQuantity": 40,
        "defaultVolume": 0
      },
      {
        "sourceRow": 12,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "95%酒精（ml）",
        "unit": "ml",
        "standardQuantity": 30,
        "defaultVolume": 0
      },
      {
        "sourceRow": 13,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "黄色垃圾袋（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 14,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "大中单（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.333,
        "defaultVolume": 0
      },
      {
        "sourceRow": 15,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "小中单（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 16,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "手术衣（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 17,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "20毫升注射器（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 18,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "酶液（ml/天）",
        "unit": "ml/天",
        "standardQuantity": 80,
        "defaultVolume": 0
      },
      {
        "sourceRow": 19,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "戊二醛（瓶/天）",
        "unit": "瓶/天",
        "standardQuantity": 0.429,
        "defaultVolume": 0
      },
      {
        "sourceRow": 20,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "纱布块",
        "unit": "",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 21,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 22,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "帽子（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 23,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "PVC手套（只）",
        "unit": "只",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 24,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "橡胶手套（只）",
        "unit": "只",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 25,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 26,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "鼻氧管（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 27,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "5毫升蓝色注射器（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 28,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "双面胶（cm）",
        "unit": "cm",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 29,
        "serviceGroup": "麻醉室",
        "careType": "other",
        "materialName": "咬口器（胃镜）（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "endoscopy",
    "department": "胃肠镜",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "输液器（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "20毫升耦合剂（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "75%酒精（ml）",
        "unit": "ml",
        "standardQuantity": 30,
        "defaultVolume": 0
      },
      {
        "sourceRow": 7,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "棉签（个）",
        "unit": "个",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 8,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "碘伏（ml）",
        "unit": "ml",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 9,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "止血带（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 10,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "卫生纸（节）",
        "unit": "节",
        "standardQuantity": 20,
        "defaultVolume": 0
      },
      {
        "sourceRow": 11,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "84消毒液（ml/天）",
        "unit": "ml/天",
        "standardQuantity": 40,
        "defaultVolume": 0
      },
      {
        "sourceRow": 12,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "95%酒精（ml）",
        "unit": "ml",
        "standardQuantity": 30,
        "defaultVolume": 0
      },
      {
        "sourceRow": 13,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "黄色垃圾袋（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 14,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "大中单（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.333,
        "defaultVolume": 0
      },
      {
        "sourceRow": 15,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "小中单（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 16,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "手术衣（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 17,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "20毫升注射器（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 18,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "酶液（ml/天）",
        "unit": "ml/天",
        "standardQuantity": 80,
        "defaultVolume": 0
      },
      {
        "sourceRow": 19,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "戊二醛（瓶/天）",
        "unit": "瓶/天",
        "standardQuantity": 0.429,
        "defaultVolume": 0
      },
      {
        "sourceRow": 20,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "纱布块",
        "unit": "",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 21,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 22,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "帽子（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 0
      },
      {
        "sourceRow": 23,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "PVC手套（只）",
        "unit": "只",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 24,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "橡胶手套（只）",
        "unit": "只",
        "standardQuantity": 4,
        "defaultVolume": 0
      },
      {
        "sourceRow": 25,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 26,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "鼻氧管（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 27,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "5毫升蓝色注射器（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 28,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "双面胶（cm）",
        "unit": "cm",
        "standardQuantity": 5,
        "defaultVolume": 0
      },
      {
        "sourceRow": 29,
        "serviceGroup": "胃肠镜",
        "careType": "other",
        "materialName": "咬口器（胃镜）（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "inspection",
    "department": "检查室",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "检查手套（支）",
        "unit": "支",
        "standardQuantity": 1,
        "defaultVolume": 15
      },
      {
        "sourceRow": 5,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "薄膜手套（副）",
        "unit": "副",
        "standardQuantity": 1,
        "defaultVolume": 15
      },
      {
        "sourceRow": 6,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "白色铺巾（张）",
        "unit": "张",
        "standardQuantity": 1,
        "defaultVolume": 15
      },
      {
        "sourceRow": 7,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "石蜡油（ml）",
        "unit": "ml",
        "standardQuantity": 5,
        "defaultVolume": 15
      },
      {
        "sourceRow": 8,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "卫生纸（节）",
        "unit": "节",
        "standardQuantity": 2,
        "defaultVolume": 15
      },
      {
        "sourceRow": 9,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "碘伏棉球（粒）",
        "unit": "粒",
        "standardQuantity": 3,
        "defaultVolume": 15
      },
      {
        "sourceRow": 10,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "棉纱布（块）",
        "unit": "块",
        "standardQuantity": 3,
        "defaultVolume": 15
      },
      {
        "sourceRow": 11,
        "serviceGroup": "新病号/复查病患",
        "careType": "other",
        "materialName": "胶带（cm）",
        "unit": "cm",
        "standardQuantity": 80,
        "defaultVolume": 15
      },
      {
        "sourceRow": 12,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "医疗垃圾袋（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 1
      },
      {
        "sourceRow": 13,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "黑色垃圾袋（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 1
      },
      {
        "sourceRow": 14,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "84消毒液（ml）",
        "unit": "ml",
        "standardQuantity": 10,
        "defaultVolume": 1
      },
      {
        "sourceRow": 15,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "洗手液（ml）",
        "unit": "ml",
        "standardQuantity": 10,
        "defaultVolume": 1
      },
      {
        "sourceRow": 16,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "手消（ml）",
        "unit": "ml",
        "standardQuantity": 10,
        "defaultVolume": 1
      },
      {
        "sourceRow": 17,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 2,
        "defaultVolume": 1
      },
      {
        "sourceRow": 18,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "标签贴（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.286,
        "defaultVolume": 1
      },
      {
        "sourceRow": 19,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "处方签",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      },
      {
        "sourceRow": 20,
        "serviceGroup": "每天固定使用",
        "careType": "other",
        "materialName": "中单（个/天）",
        "unit": "个/天",
        "standardQuantity": 0.429,
        "defaultVolume": 1
      }
    ]
  },
  {
    "key": "logistics",
    "department": "后勤保洁",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "后勤保洁",
        "careType": "other",
        "materialName": "垃圾袋（个/天）",
        "unit": "个/天",
        "standardQuantity": 46,
        "defaultVolume": 1
      },
      {
        "sourceRow": 5,
        "serviceGroup": "后勤保洁",
        "careType": "other",
        "materialName": "84消毒液（瓶/天）",
        "unit": "瓶/天",
        "standardQuantity": 0.133,
        "defaultVolume": 1
      },
      {
        "sourceRow": 6,
        "serviceGroup": "后勤保洁",
        "careType": "other",
        "materialName": "洁厕灵（瓶/天）",
        "unit": "瓶/天",
        "standardQuantity": 0.067,
        "defaultVolume": 1
      },
      {
        "sourceRow": 7,
        "serviceGroup": "后勤保洁",
        "careType": "other",
        "materialName": "口罩（个/天）",
        "unit": "个/天",
        "standardQuantity": 1,
        "defaultVolume": 1
      },
      {
        "sourceRow": 8,
        "serviceGroup": "后勤保洁",
        "careType": "other",
        "materialName": "拖把、扫把等",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 1
      }
    ]
  },
  {
    "key": "western-pharmacy",
    "department": "西药房",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "西药房",
        "careType": "other",
        "materialName": "小透明袋子（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "西药房",
        "careType": "other",
        "materialName": "打印纸",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "cashier",
    "department": "收费室",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "收费室",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 8,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "收费室",
        "careType": "other",
        "materialName": "A4纸（张）",
        "unit": "张",
        "standardQuantity": 3,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "收费室",
        "careType": "other",
        "materialName": "针式打印纸",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      }
    ]
  },
  {
    "key": "tcm-pharmacy",
    "department": "中药房",
    "monthDays": 30,
    "lines": [
      {
        "sourceRow": 4,
        "serviceGroup": "中药房",
        "careType": "other",
        "materialName": "小透明袋子（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 5,
        "serviceGroup": "中药房",
        "careType": "other",
        "materialName": "大袋子（个）",
        "unit": "个",
        "standardQuantity": 1,
        "defaultVolume": 0
      },
      {
        "sourceRow": 6,
        "serviceGroup": "中药房",
        "careType": "other",
        "materialName": "Pvc手套（盒/天）",
        "unit": "盒/天",
        "standardQuantity": 0.033,
        "defaultVolume": 0
      },
      {
        "sourceRow": 7,
        "serviceGroup": "中药房",
        "careType": "other",
        "materialName": "针式打印纸",
        "unit": "",
        "standardQuantity": null,
        "defaultVolume": 0
      }
    ]
  }
] as const;

export const departmentTemplateByKey = new Map(departmentConsumptionTemplates.map(template => [template.key, template]));

