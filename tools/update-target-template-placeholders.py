from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile
import html
import os
import re
import shutil
import tempfile


ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "coshare_patientrecord_sys_backend/src/main/resources/medical-record-templates/target-medical-record-template.docx"

REPLACEMENTS = {
    1: "姓名:${patientName} 性别:${gender} 年龄:${age} 籍贯:${nativePlace}",
    2: "职业:${occupation} 婚姻:${maritalStatus} 民族:${nation} 入院日期:${admissionDate}",
    3: "家庭住址:${address} 病史采集日期:${historyCollectedAt}",
    4: "联系人:${contactName} 病史陈述者：${historyProvider} 与患者关系:${contactRelation}",
    5: "联系人电话:${contactPhone} 联系人地址:${contactAddress} 陈述内容是否可靠：${historyReliable}",
    6: "发病节气:${solarTermOnset} 过敏药物：${allergyHistory}",
    8: "现病史：${presentIllnessText}\n门诊收入院原因：${admissionReason}\n一般情况：${generalConditionText}",
    11: "婚育史：${marriageBirthHistory}",
    13: "中医“四诊”观察结果描述：${tcmFourDiagnosisText}\n舌象：${tongue}  脉象：${pulseCondition}",
    15: "生命体征：${vitalSigns}",
    16: "一般状况检查结果：${generalExam}",
    17: "皮肤和黏膜检查结果：${skinMucosaExam}",
    18: "全身浅表淋巴结检查结果：${lymphNodeExam}\n头部及其器官检查结果：${headOrganExam}",
    19: "颈部检查结果：${neckExam}",
    20: "胸部检查结果：${chestExam}",
    21: "腹部检查结果：${abdomenExam}",
    23: "外生殖器检查结果：${externalGenitaliaExam}",
    24: "脊柱四肢检查结果：${spineLimbsExam}",
    25: "神经系统检查结果：${nervousSystemExam}",
    31: "",
    34: "西医诊断：${westernDiagnosis}",
    35: "",
    38: "医师签名：${doctorSignature}",
    39: "副主任医师签名：${seniorDoctorSignature}",
    41: "入院日期:${admissionDate} ${firstCourseAdmissionTime}",
    42: "姓名:${patientName} 性别:${gender} 年龄:${age}，以“${chiefComplaintText}”为主诉，门诊“${westernDiagnosis}”为诊断收住入院",
    43: "病例特点：${firstCourseCaseFeatures}",
    45: "${tcmSyndromeBasis}",
    47: "${westernDiagnosisBasis}",
    48: "",
    49: "",
    51: "${tcmDifferentialDiagnosis}",
    53: "${westernDifferentialDiagnosis}",
    55: "中医诊断：${tcmDiagnosis}",
    56: "西医诊断：${westernDiagnosis}",
    57: "",
    58: "诊疗计划：${diagnosisTreatmentPlan}",
    59: "",
    60: "",
    61: "",
    62: "",
    63: "",
    64: "",
    65: "",
    67: "医师签名：${doctorSignature}",
    68: "副主任医师签名：${seniorDoctorSignature}",
    69: "主治/副主任医师查房记录",
    70: "${attendingRoundsJson}",
    71: "医师签名：${doctorSignature}",
    72: "手术医师查房记录",
    73: "${surgeonPreOpRoundText}",
    74: "医师签名：${doctorSignature}",
    76: "${preOpSummary}",
    77: "术前诊断：${preOpDiagnosis}",
    78: "",
    79: "手术指征：${operationIndication}",
    80: "",
    81: "",
    82: "",
    85: "注意事项：${operationNotes}",
    86: "",
    87: "",
    88: "术前准备：${preOpPreparation}",
    89: "",
    90: "",
    91: "",
    92: "医师签字：${doctorSignature}",
    93: "术后首次病程记录 手术时间：${surgeryDate} 术中诊断：${intraoperativeDiagnosis}",
    94: "",
    96: "手术方式：${operationName} 手术简要经过：${operationBriefProcess}",
    97: "",
    98: "",
    99: "术后诊疗计划：${postOpTreatmentPlan}",
    100: "",
    101: "",
    102: "医师签字：${doctorSignature}",
    103: "记录日期时间：${postOpFirstRecordAt}",
    104: "${postOpRoundsJson}",
    105: "",
    106: "",
    107: "",
    108: "",
    109: "",
    110: "",
    111: "",
    112: "",
    113: "",
    114: "",
    115: "",
    116: "",
    117: "",
    118: "",
    120: "",
    121: "",
    122: "",
    123: "",
    126: "入院情况：${dischargeAdmissionSituation}",
    128: "中医诊断：${tcmDiagnosis}",
    129: "西医诊断：${westernDiagnosis}",
    130: "",
    131: "诊治经过及结果（含手术日期名称及结果）：${dischargeTreatmentResult}",
    132: "出院诊断：${dischargeDiagnosis}",
    133: "出院情况（含治疗效果）：${dischargeCondition}",
    134: "出院医嘱：${dischargeAdvice}",
    135: "",
    136: "",
    137: "",
    138: "",
    139: "",
    140: "中医调护：${tcmCareAdvice}",
    141: "",
    142: "",
    143: "",
    145: "医师签名：${doctorSignature}",
}

P_RE = re.compile(r"<w:p[\s\S]*?</w:p>")
T_RE = re.compile(r"(<w:t\b[^>]*>)(.*?)(</w:t>)", re.S)


def set_para_text(paragraph: str, text: str) -> str:
    escaped = html.escape(text, quote=False)
    seen = False

    def replace(match: re.Match[str]) -> str:
        nonlocal seen
        if not seen:
            seen = True
            return match.group(1) + escaped + match.group(3)
        return match.group(1) + match.group(3)

    return T_RE.sub(replace, paragraph)


def main() -> None:
    with ZipFile(TEMPLATE, "r") as source:
        infos = source.infolist()
        entries = {info.filename: source.read(info.filename) for info in infos}

    xml = entries["word/document.xml"].decode("utf-8")
    chunks: list[str] = []
    last = 0
    matches = list(P_RE.finditer(xml))
    for index, match in enumerate(matches):
        chunks.append(xml[last:match.start()])
        paragraph = match.group(0)
        if index in REPLACEMENTS:
            paragraph = set_para_text(paragraph, REPLACEMENTS[index])
        chunks.append(paragraph)
        last = match.end()
    chunks.append(xml[last:])
    entries["word/document.xml"] = "".join(chunks).encode("utf-8")

    handle, temp_name = tempfile.mkstemp(suffix=".docx")
    os.close(handle)
    Path(temp_name).unlink(missing_ok=True)
    with ZipFile(temp_name, "w", ZIP_DEFLATED) as target:
        written: set[str] = set()
        for info in infos:
            if info.filename in written:
                continue
            target.writestr(info, entries[info.filename])
            written.add(info.filename)
    shutil.move(temp_name, TEMPLATE)
    print(f"updated target template paragraphs: {len(REPLACEMENTS)}")


if __name__ == "__main__":
    main()
