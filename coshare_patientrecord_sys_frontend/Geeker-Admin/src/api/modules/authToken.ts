import { LOGIN_URL } from "@/config";
import { useUserStore } from "@/stores/modules/user";

const USER_STORE_KEY = "geeker-user";
const AUTH_EXPIRED_MESSAGE = "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55";

let redirectingToLogin = false;

export class AuthExpiredError extends Error {
  status = 401;

  constructor(message = AUTH_EXPIRED_MESSAGE) {
    super(message);
    this.name = "AuthExpiredError";
  }
}

export const getStoredAccessToken = () => {
  try {
    const raw = localStorage.getItem(USER_STORE_KEY);
    if (!raw) return "";
    const store = JSON.parse(raw) as { token?: string };
    return typeof store.token === "string" ? store.token : "";
  } catch {
    return "";
  }
};

export const getAccessToken = () => {
  try {
    const userStore = useUserStore();
    if (typeof userStore.token === "string" && userStore.token) return userStore.token;
  } catch {
    // Pinia may not be active for early module-level calls; fall back to persisted state.
  }
  return getStoredAccessToken();
};

export const authHeaders = (headers: HeadersInit = {}) => {
  const token = getAccessToken();
  return token ? { ...headers, "x-access-token": token } : headers;
};

export const clearStoredAuthSession = () => {
  try {
    const userStore = useUserStore();
    userStore.setToken("");
  } catch {
    // Store is not always available from standalone fetch helpers.
  }
  try {
    const raw = localStorage.getItem(USER_STORE_KEY);
    if (!raw) return;
    const store = JSON.parse(raw) as Record<string, unknown>;
    localStorage.setItem(USER_STORE_KEY, JSON.stringify({ ...store, token: "" }));
  } catch {
    localStorage.removeItem(USER_STORE_KEY);
  }
};

export const handleUnauthorizedResponse = (message = AUTH_EXPIRED_MESSAGE): never => {
  clearStoredAuthSession();
  if (typeof window !== "undefined" && !redirectingToLogin) {
    const normalizedLogin = LOGIN_URL.startsWith("/") ? LOGIN_URL : `/${LOGIN_URL}`;
    const currentHashPath = window.location.hash.replace(/^#/, "") || "/";
    redirectingToLogin = true;
    if (currentHashPath.toLowerCase() !== normalizedLogin.toLowerCase()) {
      window.location.hash = normalizedLogin;
    }
    window.setTimeout(() => {
      redirectingToLogin = false;
    }, 1200);
  }
  throw new AuthExpiredError(message);
};

export const isUnauthorizedApiResponse = (result: Response, payload?: { code?: unknown }) =>
  result.status === 401 || String(payload?.code ?? "") === "401";
