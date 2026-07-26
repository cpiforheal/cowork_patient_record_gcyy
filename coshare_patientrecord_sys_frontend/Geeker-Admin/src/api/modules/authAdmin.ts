import type { ResultData } from "@/api/interface";
import { authHeaders, handleUnauthorizedResponse } from "./authToken";

const AUTH_API_BASE_URL = import.meta.env.VITE_AUTH_API_BASE_URL || "/auth";

export interface RoleDescriptor {
  role: string;
  name: string;
  responsibility: string;
  entries: string[];
  actions: string[];
  dataScope: string;
  memberCount: number;
}

export interface AdminAccountSummary {
  id: string;
  username: string;
  name: string;
  role: string;
  roleLabel: string;
  status: "启用" | "停用";
  departmentIds: string[];
  primaryDepartmentId: string;
  department: string;
  scope: string;
}

export interface AccountUpsertRequest {
  username: string;
  name: string;
  role: string;
  status: "启用" | "停用";
  password?: string;
  departmentIds: string[];
  primaryDepartmentId: string;
  scope?: string;
}

const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(`${AUTH_API_BASE_URL}${path}`, {
    ...init,
    headers: authHeaders(init?.headers)
  });
  if (response.status === 401) handleUnauthorizedResponse();

  const text = await response.text();
  let payload: ResultData<T>;
  try {
    payload = JSON.parse(text) as ResultData<T>;
  } catch {
    throw new Error("账号权限接口返回格式异常");
  }
  if (!response.ok || String(payload.code) !== "200") {
    throw new Error(payload.msg || `账号权限接口请求失败（${response.status}）`);
  }
  return payload.data;
};

export const getAdminRoleCatalogApi = () => request<RoleDescriptor[]>("/admin/roles");

export const getAdminAccountsApi = () => request<AdminAccountSummary[]>("/admin/accounts");

export const createAdminAccountApi = (payload: AccountUpsertRequest) =>
  request<AdminAccountSummary>("/admin/accounts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });

export const updateAdminAccountApi = (id: string, payload: AccountUpsertRequest) =>
  request<AdminAccountSummary>(`/admin/accounts/${encodeURIComponent(id)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });

export const resetAdminAccountPasswordApi = (id: string, newPassword: string) =>
  request<{ ok: boolean }>(`/admin/accounts/${encodeURIComponent(id)}/password`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ newPassword })
  });

export const deleteAdminAccountApi = (id: string) =>
  request<{ ok: boolean }>(`/admin/accounts/${encodeURIComponent(id)}`, { method: "DELETE" });
