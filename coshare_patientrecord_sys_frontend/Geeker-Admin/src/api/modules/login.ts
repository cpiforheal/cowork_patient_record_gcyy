import { Login, ResultData } from "@/api/interface/index";
import authMenuList from "@/assets/json/authMenuList.json";
import authButtonList from "@/assets/json/authButtonList.json";
import { authHeaders } from "./authToken";

const AUTH_API_BASE_URL = import.meta.env.VITE_AUTH_API_BASE_URL || "/auth";

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

export const loginApi = (params: Login.ReqLoginForm) => {
  return fetch(`${AUTH_API_BASE_URL}/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params)
  })
    .then(async response => {
      const payload = (await response.json()) as ResultData<Login.ResLogin>;
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
      const payload = (await response.json()) as ResultData<LoginOptions>;
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
      const payload = (await response.json()) as ResultData<{ accounts: LoginAccountOption[] }>;
      if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "账号列表加载失败");
      return payload;
    })
    .catch(error => {
      throw error instanceof Error ? error : new Error("登录选项服务未连通，请确认后端已启动");
    });
};

export const getAuthMenuListApi = () => {
  return Promise.resolve(authMenuList as unknown as ResultData<Menu.MenuOptions[]>);
};

export const getAuthButtonListApi = () => {
  return Promise.resolve(authButtonList as unknown as ResultData<Login.ResAuthButtons>);
};

export const logoutApi = () => {
  return fetch(`${AUTH_API_BASE_URL}/logout`, {
    method: "POST",
    headers: authHeaders()
  }).then(async response => {
    const payload = (await response.json()) as ResultData<null>;
    if (!response.ok || String(payload.code) !== "200") throw new Error(payload.msg || "退出登录失败");
    return payload;
  });
};
