import { RouteRecordRaw } from "vue-router";
import { getShowMenuList } from "@/utils";

export const INVENTORY_SYSTEM_PREFIX = "/inventory-system";
export const INVENTORY_SYSTEM_DASHBOARD = `${INVENTORY_SYSTEM_PREFIX}/dashboard`;

const tabRouteNames: Record<string, string> = {
  overview: "inventoryOverview",
  executive: "inventoryExecutive",
  requests: "inventoryRequests",
  stock: "inventoryStock",
  items: "inventoryItems",
  weekly: "inventoryWeekly",
  controls: "inventoryControls",
  packages: "inventoryPackages",
  trace: "inventoryTrace",
  daily: "inventoryDaily",
  roles: "inventoryRoles"
};

const tabCapabilities: Record<string, readonly string[]> = {
  overview: ["inventory:request", "inventory:receive", "inventory:approve", "inventory:issue", "inventory:count", "inventory:export"],
  executive: ["inventory:report", "inventory:approve", "inventory:issue", "inventory:count"],
  requests: ["inventory:request", "inventory:approve", "inventory:issue", "inventory:receive"],
  stock: ["inventory:issue", "inventory:export", "inventory:item:manage"],
  items: ["inventory:item:manage", "inventory:rule"],
  weekly: ["inventory:request", "inventory:approve", "inventory:count", "inventory:export"],
  controls: ["inventory:count", "inventory:receive"],
  packages: ["inventory:read", "inventory:approve", "inventory:rule"],
  trace: ["inventory:export", "inventory:issue", "inventory:count"],
  daily: ["inventory:read"],
  roles: ["inventory:role:manage"]
};

type InventoryMenuItem = Omit<Menu.MenuOptions, "children"> & {
  tab?: string;
  children?: InventoryMenuItem[];
};

const departmentEntries = [
  { key: "physiotherapy", title: "理疗室", icon: "Odometer" },
  { key: "laboratory", title: "检验科", icon: "Histogram" },
  { key: "nursing", title: "护理部", icon: "FirstAidKit" },
  { key: "tcm", title: "中医科", icon: "Medicine" },
  { key: "operating", title: "手术室", icon: "KnifeFork" },
  { key: "anesthesia", title: "麻醉室", icon: "Monitor" },
  { key: "endoscopy", title: "胃肠镜", icon: "View" },
  { key: "inspection", title: "检查室", icon: "Search" },
  { key: "logistics", title: "后勤保洁", icon: "Brush" },
  { key: "western-pharmacy", title: "西药房", icon: "Shop" },
  { key: "cashier", title: "收费室", icon: "Money" },
  { key: "tcm-pharmacy", title: "中药房", icon: "Goods" }
] as const;

const meta = (title: string, icon: string, isHide = false) => ({
  icon,
  title,
  isLink: "",
  isHide,
  isFull: false,
  isAffix: false,
  isKeepAlive: true
});

const inventoryMenu: InventoryMenuItem[] = [
  {
    path: INVENTORY_SYSTEM_DASHBOARD,
    name: "inventorySystemDashboard",
    tab: "overview",
    meta: meta("耗材总览", "DataBoard")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/departments`,
    name: "inventorySystemDepartmentEntry",
    meta: meta("科室耗材录入", "OfficeBuilding"),
    children: departmentEntries.map(entry => ({
      path: `${INVENTORY_SYSTEM_PREFIX}/departments/${entry.key}`,
      name: `inventorySystemDepartment${entry.key.replace(/(^|-)([a-z])/g, (_, _dash, letter) => letter.toUpperCase())}`,
      tab: "packages",
      meta: meta(entry.title, entry.icon)
    }))
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/materials`,
    name: "inventorySystemMaterialsMenu",
    meta: meta("库存与出入", "Goods"),
    children: [
      { path: `${INVENTORY_SYSTEM_PREFIX}/stock`, name: "inventorySystemStock", tab: "stock", meta: meta("库存台账与入库", "Goods") },
      { path: `${INVENTORY_SYSTEM_PREFIX}/requests`, name: "inventorySystemRequests", tab: "requests", meta: meta("科室申领与签收", "List") },
      { path: `${INVENTORY_SYSTEM_PREFIX}/trace`, name: "inventorySystemTrace", tab: "trace", meta: meta("出入库明细", "TrendCharts") },
      { path: `${INVENTORY_SYSTEM_PREFIX}/controls`, name: "inventorySystemControls", tab: "controls", meta: meta("盘点与损耗", "Checked") }
    ]
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/governance`,
    name: "inventorySystemPatientVolume",
    meta: meta("患者量扣减", "Collection"),
    children: [
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/consumable-entry`,
        name: "inventorySystemConsumableEntry",
        tab: "packages",
        meta: meta("耗材规则录入", "EditPen")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/packages`,
        name: "inventorySystemPackages",
        tab: "packages",
        meta: meta("扣减规则与异常", "Collection")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/weekly`,
        name: "inventorySystemWeekly",
        tab: "weekly",
        meta: meta("周用量核对", "Calendar")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/daily-verification`,
        name: "inventorySystemDailyVerification",
        tab: "daily",
        meta: meta("患者变量日核表", "Tickets")
      }
    ]
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/analytics`,
    name: "inventorySystemAnalyticsMenu",
    meta: meta("物资设置与统计", "DataAnalysis"),
    children: [
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/items`,
        name: "inventorySystemItems",
        tab: "items",
        meta: meta("物资档案", "Document")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/executive`,
        name: "inventorySystemExecutive",
        tab: "executive",
        meta: meta("管理看板", "DataAnalysis")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/role-management`,
        name: "inventorySystemRoleManagement",
        tab: "roles",
        meta: meta("岗位与权限", "UserFilled")
      }
    ]
  }
];

const canAccessTab = (tab: string, buttonPermissions: Record<string, string[]>, capabilities: string[]) => {
  const required = tabCapabilities[tab] || [];
  const granted = new Set([...(buttonPermissions[tabRouteNames[tab]] || []), ...capabilities]);
  return required.some(code => granted.has(code));
};

const filterInventoryMenu = (items: InventoryMenuItem[], buttonPermissions: Record<string, string[]>, capabilities: string[]) => {
  const result: Menu.MenuOptions[] = [];
  items.forEach(item => {
    if (item.children?.length) {
      const children = filterInventoryMenu(item.children as InventoryMenuItem[], buttonPermissions, capabilities);
      if (children.length) {
        const { tab: _tab, children: _children, ...menuItem } = item;
        result.push({ ...menuItem, children });
      }
      return;
    }
    if (item.tab && !canAccessTab(item.tab, buttonPermissions, capabilities)) return;
    const { tab: _tab, children: _children, ...menuItem } = item;
    result.push(menuItem);
  });
  return result;
};

export const getInventorySystemMenu = (buttonPermissions: Record<string, string[]>, capabilities: string[]) =>
  getShowMenuList(filterInventoryMenu(inventoryMenu, buttonPermissions, capabilities));

const filterMedicalMenu = (items: Menu.MenuOptions[]): Menu.MenuOptions[] =>
  items.flatMap(item => {
    if (isLegacyInventoryPath(item.path)) return [];
    if (!item.children?.length) return [item];

    const children = filterMedicalMenu(item.children);
    return children.length ? [{ ...item, children }] : [];
  });

export const getMedicalSystemMenu = (items: Menu.MenuOptions[]) => getShowMenuList(filterMedicalMenu(items));

// 独立进销存系统暂只向具有岗位权限管理权的管理员开放；其他岗位仍只进入门诊管理平台。
export const hasInventorySystemAccess = (buttonPermissions: Record<string, string[]>, _capabilities: string[]) =>
  (buttonPermissions.inventoryRoles || []).includes("inventory:role:manage");

export const hasMedicalSystemAccess = (menus: Menu.MenuOptions[]) =>
  getMedicalSystemMenu(menus).length > 0;

export const isInventorySystemPath = (path: string) => path === INVENTORY_SYSTEM_PREFIX || path.startsWith(`${INVENTORY_SYSTEM_PREFIX}/`);

export const isLegacyInventoryPath = (path: string) => path === "/inventory" || path.startsWith("/inventory/");

export const inventorySystemPathForLegacy = (path: string) => {
  const tab = path === "/inventory" || path === "/inventory/manage" ? "overview" : path.split("/").pop() || "overview";
  return tab === "overview" ? INVENTORY_SYSTEM_DASHBOARD : `${INVENTORY_SYSTEM_PREFIX}/${tab}`;
};

const inventoryView = () => import("@/views/inventory/manage/index.vue");
const redirectTo = (path: string) => () => path;

export const inventorySystemRoutes: RouteRecordRaw[] = [
  {
    path: INVENTORY_SYSTEM_DASHBOARD,
    name: "inventorySystemDashboard",
    component: inventoryView,
    meta: meta("耗材总览", "DataBoard")
  },
  { path: `${INVENTORY_SYSTEM_PREFIX}/materials`, name: "inventorySystemMaterials", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/stock`), meta: meta("库存管理", "Box") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/operations`, name: "inventorySystemOperations", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/requests`), meta: meta("申领、计划与库存", "Tickets") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/governance`, name: "inventorySystemGovernance", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/packages`), meta: meta("患者量核算", "SetUp") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/departments`, name: "inventorySystemDepartments", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/departments/${departmentEntries[0].key}`), meta: meta("科室耗材录入", "OfficeBuilding") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/audit`, name: "inventorySystemAudit", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/controls`), meta: meta("盘点与追溯", "Checked") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/analytics`, name: "inventorySystemAnalytics", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/executive`), meta: meta("统计与分析", "PieChart") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/stock`, name: "inventorySystemStock", component: inventoryView, meta: meta("库存台账与入库", "Goods") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/items`, name: "inventorySystemItems", component: inventoryView, meta: meta("物资档案", "Document") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/requests`, name: "inventorySystemRequests", component: inventoryView, meta: meta("科室申领与签收", "List") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/weekly`, name: "inventorySystemWeekly", component: inventoryView, meta: meta("周用量核对", "Calendar") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/consumable-entry`, name: "inventorySystemConsumableEntry", component: inventoryView, meta: meta("耗材规则录入", "EditPen") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/packages`, name: "inventorySystemPackages", component: inventoryView, meta: meta("扣减规则与异常", "Collection") },
  ...departmentEntries.map(entry => ({
    path: `${INVENTORY_SYSTEM_PREFIX}/departments/${entry.key}`,
    name: `inventorySystemDepartment${entry.key.replace(/(^|-)([a-z])/g, (_, _dash, letter) => letter.toUpperCase())}`,
    component: inventoryView,
    meta: meta(entry.title, entry.icon)
  })),
  { path: `${INVENTORY_SYSTEM_PREFIX}/controls`, name: "inventorySystemControls", component: inventoryView, meta: meta("盘点与损耗", "EditPen") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/trace`, name: "inventorySystemTrace", component: inventoryView, meta: meta("出入库明细", "TrendCharts") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/executive`, name: "inventorySystemExecutive", component: inventoryView, meta: meta("管理看板", "DataAnalysis") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/daily-verification`, name: "inventorySystemDailyVerification", component: inventoryView, meta: meta("患者变量日核表", "Tickets") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/role-management`, name: "inventorySystemRoleManagement", component: inventoryView, meta: meta("岗位与权限", "UserFilled") }
];
