import { defineStore } from "pinia";
import { getAuthNavigationApi, switchActiveDepartmentApi } from "@/api/modules/login";
import { AuthState } from "@/stores/interface";
import { useUserStore } from "@/stores/modules/user";
import { getAllBreadcrumbList, getFlatMenuList, getShowMenuList } from "@/utils";

export const useAuthStore = defineStore({
  id: "geeker-auth",
  state: (): AuthState => ({
    authButtonList: {},
    authMenuList: [],
    navigationVersion: "",
    policyVersion: "",
    shortcuts: [],
    activeDepartment: undefined,
    departments: [],
    capabilities: [],
    stagePermissions: {},
    auxiliaryPermissions: {},
    routeName: ""
  }),
  getters: {
    authButtonListGet: state => state.authButtonList,
    authMenuListGet: state => state.authMenuList,
    shortcutsGet: state => state.shortcuts,
    showMenuListGet: state => getShowMenuList(state.authMenuList),
    flatMenuListGet: state => getFlatMenuList(state.authMenuList),
    breadcrumbListGet: state => getAllBreadcrumbList(state.authMenuList)
  },
  actions: {
    async getNavigation() {
      const { data } = await getAuthNavigationApi();
      this.navigationVersion = data.version;
      this.policyVersion = data.policyVersion;
      this.authMenuList = data.menus;
      this.authButtonList = data.buttonPermissions;
      this.shortcuts = data.shortcuts;
      this.activeDepartment = data.activeDepartment;
      this.departments = data.departments;
      this.capabilities = data.capabilities;
      this.stagePermissions = data.stagePermissions;
      this.auxiliaryPermissions = data.auxiliaryPermissions;
    },
    async switchActiveDepartment(departmentId: string) {
      const { data } = await switchActiveDepartmentApi(departmentId);
      const userStore = useUserStore();
      userStore.setUserInfo({
        ...userStore.userInfo,
        activeDepartmentId: data.id,
        department: data.name
      });
      await this.getNavigation();
    },
    async getAuthButtonList() {
      await this.getNavigation();
    },
    async getAuthMenuList() {
      await this.getNavigation();
    },
    async setRouteName(name: string) {
      this.routeName = name;
    }
  }
});
