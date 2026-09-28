/**
 * 复制文本到剪贴板（兼容非安全上下文）。
 *
 * 背景：浏览器的 navigator.clipboard 只在「安全上下文」（HTTPS 或 localhost）下可用。
 * 院内系统通过 http://内网IP:8848 访问，属于非安全上下文，此时 navigator.clipboard
 * 为 undefined，直接调用会抛错并误报「复制失败，请手动选择复制」——而同一账号在服务器
 * 本机用 localhost 打开却正常，原因就在这里。
 *
 * 因此统一封装：能用新接口就用，不能用就回退到 textarea + execCommand
 * （同样由用户点击触发，非安全上下文下可正常复制）。
 *
 * @param text 待复制文本
 * @returns 是否复制成功
 */
export const copyTextToClipboard = async (text: string): Promise<boolean> => {
  const value = text ?? "";
  if (!value) return false;

  if (globalThis.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value);
      return true;
    } catch {
      // 权限被拒或非用户手势触发时，继续尝试回退方案
    }
  }

  return copyByTextarea(value);
};

const copyByTextarea = (value: string): boolean => {
  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.setAttribute("readonly", "");
  textarea.style.position = "fixed";
  textarea.style.top = "-1000px";
  textarea.style.left = "-1000px";
  textarea.style.opacity = "0";
  document.body.appendChild(textarea);
  try {
    textarea.select();
    textarea.setSelectionRange(0, value.length);
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    textarea.remove();
  }
};
