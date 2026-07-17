import { defineStore } from "pinia";
import { getAuthNavigationApi } from "@/api/modules/login";
import { AuthState } from "@/stores/interface";
import { getAllBreadcrumbList, getFlatMenuList, getShowMenuList } from "@/utils";

export const useAuthStore = defineStore({
  id: "geeker-auth",
  state: (): AuthState => ({
    authButtonList: {},
    authMenuList: [],
    navigationVersion: "",
    shortcuts: [],
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
      this.authMenuList = data.menus;
      this.authButtonList = data.buttonPermissions;
      this.shortcuts = data.shortcuts;
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
