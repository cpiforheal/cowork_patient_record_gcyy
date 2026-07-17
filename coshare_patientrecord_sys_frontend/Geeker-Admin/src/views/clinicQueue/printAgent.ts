import type { QueuePrintPayload } from "@/api/modules/clinic/clinicQueue";

const AGENT_BASE_URLS = ["http://127.0.0.1:18848", "http://localhost:18848"];
const TERMINAL_STORAGE_KEY = "clinic-queue-print-terminal-id";

export interface LocalPrintAgentStatus {
  status: "ok";
  terminalId: string;
  terminalName: string;
  printerName: string;
  version: string;
  printers: string[];
}

export interface LocalPrintResult {
  status: "SUCCESS" | "FAILED";
  printerName: string;
  errorMessage?: string;
}

const fetchAgent = async <T>(path: string, init?: RequestInit): Promise<T> => {
  let lastError: unknown;
  for (const baseUrl of AGENT_BASE_URLS) {
    try {
      const response = await fetch(`${baseUrl}${path}`, {
        ...init,
        mode: "cors",
        cache: "no-store",
        headers: { "Content-Type": "application/json; charset=utf-8", ...(init?.headers || {}) },
        signal: AbortSignal.timeout(6500)
      });
      if (!response.ok) throw new Error((await response.text()) || `本机打印服务异常（${response.status}）`);
      return response.json() as Promise<T>;
    } catch (error) {
      lastError = error;
    }
  }
  const message = lastError instanceof Error ? lastError.message : String(lastError || "failed to fetch");
  if (/failed to fetch|networkerror|load failed/i.test(message)) {
    throw new Error("当前电脑未连接本机打印服务。请在这台前台电脑安装并启动打印代理；局域网服务器上的打印服务不能代替本机代理。");
  }
  throw lastError instanceof Error ? lastError : new Error(message);
};

export const getStoredPrintTerminalId = () => window.localStorage.getItem(TERMINAL_STORAGE_KEY) || "";

export const storePrintTerminalId = (terminalId: string) => {
  window.localStorage.setItem(TERMINAL_STORAGE_KEY, terminalId);
};

export const getLocalPrintAgentStatus = () => fetchAgent<LocalPrintAgentStatus>("/health");

export const configureLocalPrintAgent = (payload: { terminalId: string; terminalName: string; printerName: string }) =>
  fetchAgent<LocalPrintAgentStatus>("/configure", { method: "POST", body: JSON.stringify(payload) });

export const printQueueTicketLocally = (payload: QueuePrintPayload) =>
  fetchAgent<LocalPrintResult>("/print/queue-ticket", { method: "POST", body: JSON.stringify(payload) });
