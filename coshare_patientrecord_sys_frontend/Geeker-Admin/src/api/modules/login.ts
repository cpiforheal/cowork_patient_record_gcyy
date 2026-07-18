import { Login, ResultData } from "@/api/interface/index";
import { authHeaders } from "./authToken";

const AUTH_API_BASE_URL = import.meta.env.VITE_AUTH_API_BASE_URL || "/auth";

const readJsonResult = async <T>(response: Response): Promise<ResultData<T>> => {
  const text = await response.text();
  if (!text) return { code: String(response.status), msg: "" } as ResultData<T>;
  try {
    return JSON.parse(text) as ResultData<T>;
  } catch {
    return { code: String(response.status), msg: response.statusText || "接口响应格式异常" } as ResultData<T>;
  }
};

export interface LoginAccountOption {
  id: string;
  username: string;
  name: string;
  department: string;
}

export interface LoginOptions {
  departments: string[];
  accounts: LoginAccountOption[];
}

export interface NavigationShortcut {
  title: string;
  desc: string;
  icon: string;
  path: string;
}

export interface AuthDepartmentOption {
  id: string;
  code: string;
  name: string;
  primary: boolean;
  status: "ACTIVE" | "INACTIVE";
}

export interface StagePermission {
  readable: boolean;
  editable: boolean;
  correctable: boolean;
}

export interface AuxiliaryPermission {
  readable: boolean;
  editable: boolean;
  returnable: boolean;
}

export interface NavigationResult {
  version: string;
  policyVersion: string;
  menus: Menu.MenuOptions[];
  buttonPermissions: Login.ResAuthButtons;
  shortcuts: NavigationShortcut[];
  activeDepartment?: AuthDepartmentOption;
  departments: AuthDepartmentOption[];
  capabilities: string[];
  stagePermissions: Record<string, StagePermission>;
  auxiliaryPermissions: Record<string, AuxiliaryPermission>;
}

export const loginApi = (params: Login.ReqLoginForm) => {
  return fetch(`${AUTH_API_BASE_URL}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params)
  })
    .then(async response => {
      const payload = await readJsonResult<Login.ResLogin>(response);
      if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "登录失败");
      return payload;
    })
    .catch(error => {
      throw error instanceof Error ? error : new Error("登录服务未连通，请确认后端已启动");
    });
};

export const getLoginOptionsApi = () => {
  return fetch(`${AUTH_API_BASE_URL}/options`)
    .then(async response => {
      const payload = await readJsonResult<LoginOptions>(response);
      if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "登录选项加载失败");
      return payload;
    })
    .catch(error => {
      throw error instanceof Error ? error : new Error("登录选项服务未连通，请确认后端已启动");
    });
};

export const getLoginAccountsApi = (department: string) => {
  return fetch(`${AUTH_API_BASE_URL}/options/accounts?department=${encodeURIComponent(department)}`)
    .then(async response => {
      const payload = await readJsonResult<{ accounts: LoginAccountOption[] }>(response);
      if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "账号列表加载失败");
      return payload;
    })
    .catch(error => {
      throw error instanceof Error ? error : new Error("登录选项服务未连通，请确认后端已启动");
    });
};

export const getAuthNavigationApi = () => {
  return fetch(`${AUTH_API_BASE_URL}/navigation`, { headers: authHeaders() }).then(async response => {
    const payload = await readJsonResult<NavigationResult>(response);
    if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "导航权限加载失败");
    return payload;
  });
};

export const switchActiveDepartmentApi = (departmentId: string) => {
  return fetch(`${AUTH_API_BASE_URL}/active-department`, {
    method: "POST",
    headers: { ...authHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify({ departmentId })
  }).then(async response => {
    const payload = await readJsonResult<AuthDepartmentOption>(response);
    if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "活动科室切换失败");
    return payload;
  });
};

export const logoutApi = () => {
  return fetch(`${AUTH_API_BASE_URL}/logout`, {
    method: "POST",
    headers: authHeaders()
  }).then(async response => {
    const payload = await readJsonResult<null>(response);
    if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "退出登录失败");
    return payload;
  });
};

export const changePasswordApi = (params: { newPassword: string }) => {
  return fetch(`${AUTH_API_BASE_URL}/password`, {
    method: "POST",
    headers: {
      ...authHeaders(),
      "Content-Type": "application/json"
    },
    body: JSON.stringify(params)
  }).then(async response => {
    const payload = await readJsonResult<{ ok: boolean }>(response);
    if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "密码修改失败");
    return payload;
  });
};
