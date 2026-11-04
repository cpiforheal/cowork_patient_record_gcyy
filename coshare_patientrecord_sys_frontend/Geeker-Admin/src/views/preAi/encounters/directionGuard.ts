import { ElMessageBox } from "element-plus";

export const confirmTcmDirection = async (careDirection?: string) => {
  if (careDirection !== "TCM_DIGESTIVE") return true;
  try {
    await ElMessageBox.confirm(
      "当前选择“中医科消化内科”：患者不进入肛肠诊疗流程，也不取肛肠号。请确认主诉与方向。",
      "确认就诊方向",
      {
        type: "warning",
        confirmButtonText: "确认中医分支",
        cancelButtonText: "返回修改"
      }
    );
    return true;
  } catch {
    return false;
  }
};
