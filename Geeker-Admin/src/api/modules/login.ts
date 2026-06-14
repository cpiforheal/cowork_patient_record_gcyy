import { Login, ResultData } from "@/api/interface/index";
import authMenuList from "@/assets/json/authMenuList.json";
import authButtonList from "@/assets/json/authButtonList.json";
import { authenticateClinicAccountApi } from "@/api/modules/clinic";

/**
 * 登录模块
 * 当前阶段使用本地登录，避免依赖模板接口。
 */
export const loginApi = (params: Login.ReqLoginForm) => {
  return authenticateClinicAccountApi(params) as Promise<ResultData<Login.ResLogin>>;
};

// 当前阶段先使用本地业务菜单，后续直接替换为后端 RBAC 接口。
export const getAuthMenuListApi = () => {
  return Promise.resolve(authMenuList as unknown as ResultData<Menu.MenuOptions[]>);
};

// 当前阶段先使用本地按钮权限，后续直接替换为后端 RBAC 接口。
export const getAuthButtonListApi = () => {
  return Promise.resolve(authButtonList as unknown as ResultData<Login.ResAuthButtons>);
};

export const logoutApi = () => {
  return Promise.resolve({ code: 200, msg: "成功", data: null });
};
