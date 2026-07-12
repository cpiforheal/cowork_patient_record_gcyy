import { defineStore } from "pinia";
import { AuthState } from "@/stores/interface";
import { getAuthButtonListApi, getAuthMenuListApi } from "@/api/modules/login";
import { getFlatMenuList, getShowMenuList, getAllBreadcrumbList } from "@/utils";
import { useUserStore } from "@/stores/modules/user";

const AI_DOCUMENT_MENU_PATHS = ["/templates", "/templates/ai-document"];

const ROLE_MENU_PATHS: Record<string, string[]> = {
  admin: ["*"],
  frontdesk: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  inspection: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  lab: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  ecg: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  ultrasound: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  tcm: [
    "/home/index",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/tcm-pharmacy",
    "/tcm-pharmacy/workbench",
    "/tcm-pharmacy/display"
  ],
  tcmPharmacyOperator: ["/tcm-pharmacy", "/tcm-pharmacy/workbench", "/tcm-pharmacy/display"],
  pharmacist: ["/home/index", "/tcm-pharmacy", "/tcm-pharmacy/workbench", "/tcm-pharmacy/display"],
  pharmacy: ["/home/index", "/tcm-pharmacy", "/tcm-pharmacy/workbench", "/tcm-pharmacy/display"],
  decoction: ["/home/index", "/tcm-pharmacy", "/tcm-pharmacy/workbench", "/tcm-pharmacy/display"],
  doctor: [
    "/home/index",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document",
    "/tcm-pharmacy",
    "/tcm-pharmacy/workbench",
    "/tcm-pharmacy/display"
  ],
  nurse: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  quality: [
    "/home/index",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document",
    "/documents",
    "/documents/recycle",
    "/audit",
    "/audit/review",
    "/audit/log"
  ],
  nursing: [
    "/home/index",
    "/workbench/upload",
    "/workbench/lab-report",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    "/templates/ai-document"
  ],
  manager: ["/home/index", ...AI_DOCUMENT_MENU_PATHS]
};

const ROLE_BUTTONS: Record<string, Record<string, string[]>> = {
  frontdesk: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:read", "document:read"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:create", "patient:read", "patient:update"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload", "document:download"]
  },
  inspection: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:read", "document:read"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  lab: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:edit", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  ecg: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:read", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  ultrasound: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:read", "document:read"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  tcm: {
    home: ["view"],
    tcmPharmacyWorkbench: ["prescription:create", "prescription:submit", "pharmacy:read"],
    tcmPharmacyDisplayMenu: ["display:read"]
  },
  tcmPharmacyOperator: {
    tcmPharmacyWorkbench: [
      "pharmacy:read",
      "charge:confirm",
      "review:execute",
      "dispensing:execute",
      "decoction:execute",
      "pickup:execute"
    ],
    tcmPharmacyDisplayMenu: ["display:read", "announcement:play"]
  },
  pharmacist: {
    home: ["view"],
    tcmPharmacyWorkbench: ["charge:confirm", "review:execute", "dispensing:execute", "pickup:execute"],
    tcmPharmacyDisplayMenu: ["display:read", "announcement:play"]
  },
  pharmacy: {
    home: ["view"],
    tcmPharmacyWorkbench: ["charge:confirm", "review:execute", "dispensing:execute", "pickup:execute"],
    tcmPharmacyDisplayMenu: ["display:read", "announcement:play"]
  },
  decoction: {
    home: ["view"],
    tcmPharmacyWorkbench: ["decoction:execute", "pharmacy:read"],
    tcmPharmacyDisplayMenu: ["display:read"]
  },
  doctor: {
    home: ["view"],
    workbenchLabReport: ["patient:search", "field:edit", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:download"],
    tcmPharmacyWorkbench: ["prescription:create", "prescription:submit", "pharmacy:read"],
    tcmPharmacyDisplayMenu: ["display:read"]
  },
  nurse: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:read", "document:read"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  nursing: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    workbenchLabReport: ["patient:search", "field:read", "document:read"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"]
  },
  quality: {
    home: ["view"],
    workbenchLabReport: ["patient:search", "field:read", "document:read"],
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
