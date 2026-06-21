import { defineStore } from "pinia";
import { AuthState } from "@/stores/interface";
import { getAuthButtonListApi, getAuthMenuListApi } from "@/api/modules/login";
import { getFlatMenuList, getShowMenuList, getAllBreadcrumbList } from "@/utils";
import { useUserStore } from "@/stores/modules/user";

const INVENTORY_STAFF_MENU_PATHS = ["/inventory", "/inventory/overview", "/inventory/requests", "/inventory/weekly"];

const INVENTORY_QUALITY_MENU_PATHS = [
  "/inventory",
  "/inventory/overview",
  "/inventory/executive",
  "/inventory/requests",
  "/inventory/weekly",
  "/inventory/controls",
  "/inventory/trace"
];

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
    "/templates/record",
    ...INVENTORY_STAFF_MENU_PATHS
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
    "/templates/record",
    ...INVENTORY_STAFF_MENU_PATHS
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
    "/templates/record",
    ...INVENTORY_STAFF_MENU_PATHS
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
    "/templates/record",
    ...INVENTORY_STAFF_MENU_PATHS
  ],
  doctor: [
    "/home/index",
    "/patients",
    "/patients/list",
    "/patients/detail/:id",
    "/encounters",
    "/encounters/active",
    "/templates",
    "/templates/record",
    ...INVENTORY_STAFF_MENU_PATHS
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
    "/templates/record",
    ...INVENTORY_STAFF_MENU_PATHS
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
    ...INVENTORY_QUALITY_MENU_PATHS,
    "/audit",
    "/audit/review",
    "/audit/log"
  ]
};

const INVENTORY_STAFF_BUTTONS = {
  inventoryOverview: ["inventory:read", "inventory:request", "inventory:receive"],
  inventoryRequests: ["inventory:read", "inventory:request", "inventory:receive"],
  inventoryWeekly: ["inventory:read", "inventory:request"]
};

const INVENTORY_QUALITY_BUTTONS = {
  inventoryOverview: ["inventory:read", "inventory:approve", "inventory:count", "inventory:export"],
  inventoryExecutive: ["inventory:read", "inventory:approve", "inventory:count", "inventory:export"],
  inventoryRequests: ["inventory:read", "inventory:approve", "inventory:export"],
  inventoryWeekly: ["inventory:read", "inventory:count", "inventory:export"],
  inventoryControls: ["inventory:read", "inventory:count", "inventory:export"],
  inventoryTrace: ["inventory:read", "inventory:export"]
};

const ROLE_BUTTONS: Record<string, Record<string, string[]>> = {
  frontdesk: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:create", "patient:read", "patient:update"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload", "document:download"],
    ...INVENTORY_STAFF_BUTTONS
  },
  lab: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"],
    ...INVENTORY_STAFF_BUTTONS
  },
  ecg: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"],
    ...INVENTORY_STAFF_BUTTONS
  },
  ultrasound: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"],
    ...INVENTORY_STAFF_BUTTONS
  },
  doctor: {
    home: ["view"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:download"],
    ...INVENTORY_STAFF_BUTTONS
  },
  nurse: {
    home: ["view"],
    workbenchUpload: ["patient:search", "document:upload"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "field:edit", "document:read", "document:upload"],
    ...INVENTORY_STAFF_BUTTONS
  },
  quality: {
    home: ["view"],
    encounterActive: ["patient:read", "field:read"],
    recordTemplate: ["field:read"],
    patientList: ["patient:read"],
    patientDetail: ["field:read", "document:read", "document:void", "document:download"],
    documentRecycle: ["document:restore", "document:read"],
    ...INVENTORY_QUALITY_BUTTONS,
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
