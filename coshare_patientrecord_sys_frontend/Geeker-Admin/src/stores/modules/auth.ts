import { defineStore } from "pinia";
import { AuthState } from "@/stores/interface";
import { getAuthButtonListApi, getAuthMenuListApi } from "@/api/modules/login";
import { getFlatMenuList, getShowMenuList, getAllBreadcrumbList } from "@/utils";
import { useUserStore } from "@/stores/modules/user";

const ROLE_MENU_PATHS: Record<string, string[]> = {
  admin: ["*"],
  frontdesk: [
    "/home/index",
    "/workbench/upload",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record"
  ],
  lab: [
    "/home/index",
    "/workbench/upload",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record"
  ],
  ecg: [
    "/home/index",
    "/workbench/upload",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record"
  ],
  ultrasound: [
    "/home/index",
    "/workbench/upload",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record"
  ],
  doctor: [
    "/home/index",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record"
  ],
  nurse: [
    "/home/index",
    "/workbench/upload",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record"
  ],
  quality: [
    "/home/index",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/documents",
    "/documents/recycle",
    "/audit",
    "/audit/review",
    "/audit/log"
  ]
};

const ROLE_BUTTONS: Record<string, Record<string, string[]>> = {
  frontdesk: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:create", "patient:read", "patient:update"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload", "document:download"]
  },
  lab: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  ecg: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  ultrasound: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  doctor: {
    home: ["view"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:download"]
  },
  nurse: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  quality: {
    home: ["view"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "document:read", "document:void", "document:download"],
    documentRecycle: ["document:restore", "document:read"],
    auditReview: ["audit:read", "quality:approve", "quality:reject"],
    auditLog: ["audit:read", "audit:export"]
  }
};

const cloneMenu = (menuList: Menu.MenuOptions[]) => JSON.parse(JSON.stringify(menuList)) as Menu.MenuOptions[];

const filterMenuByRole = (menuList: Menu.MenuOptions[], role = "frontdesk") => {
  const allowList = ROLE_MENU_PATHS[role] ?? ROLE_MENU_PATHS.frontdesk;
  if (allowList.includes("*")) return cloneMenu(menuList);

  const allowSet = new Set(allowList);
  const walk = (items: Menu.MenuOptions[]): Menu.MenuOptions[] => {
    return items
      .map(item => {
        const children = item.children ? walk(item.children) : [];
        if (allowSet.has(item.path) || children.length) {
          return { ...item, children: children.length ? children : item.children?.filter(child => allowSet.has(child.path)) };
        }
        return null;
      })
      .filter(Boolean) as Menu.MenuOptions[];
  };

  return walk(cloneMenu(menuList));
};

const filterButtonsByRole = (buttons: AuthState["authButtonList"], role = "frontdesk") => {
  if (role === "admin") return buttons;
  return ROLE_BUTTONS[role] ?? ROLE_BUTTONS.frontdesk;
};

export const useAuthStore = defineStore({
  id: "geeker-auth",
  state: (): AuthState => ({
    authButtonList: {},
    authMenuList: [],
    routeName: ""
  }),
  getters: {
    authButtonListGet: state => state.authButtonList,
    authMenuListGet: state => state.authMenuList,
    showMenuListGet: state => getShowMenuList(state.authMenuList),
    flatMenuListGet: state => getFlatMenuList(state.authMenuList),
    breadcrumbListGet: state => getAllBreadcrumbList(state.authMenuList)
  },
  actions: {
    async getAuthButtonList() {
      const userStore = useUserStore();
      const { data } = await getAuthButtonListApi();
      this.authButtonList = filterButtonsByRole(data, userStore.userInfo.role);
    },
    async getAuthMenuList() {
      const userStore = useUserStore();
      const { data } = await getAuthMenuListApi();
      this.authMenuList = filterMenuByRole(data, userStore.userInfo.role);
    },
    async setRouteName(name: string) {
      this.routeName = name;
    }
  }
});
