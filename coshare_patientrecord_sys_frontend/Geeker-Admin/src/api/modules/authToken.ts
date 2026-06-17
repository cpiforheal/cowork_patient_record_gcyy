const USER_STORE_KEY = "geeker-user";

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

export const authHeaders = (headers: HeadersInit = {}) => {
  const token = getStoredAccessToken();
  return token ? { ...headers, "x-access-token": token } : headers;
};
