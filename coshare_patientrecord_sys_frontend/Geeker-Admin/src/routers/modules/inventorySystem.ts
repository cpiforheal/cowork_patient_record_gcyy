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
  trace: "inventoryTrace"
};

const tabCapabilities: Record<string, readonly string[]> = {
  overview: ["inventory:request", "inventory:receive", "inventory:approve", "inventory:issue", "inventory:count", "inventory:export"],
  executive: ["inventory:report", "inventory:approve", "inventory:issue", "inventory:count"],
  requests: ["inventory:request", "inventory:approve", "inventory:issue", "inventory:receive"],
  stock: ["inventory:issue", "inventory:export", "inventory:item:manage"],
  items: ["inventory:item:manage", "inventory:rule"],
  weekly: ["inventory:request", "inventory:approve", "inventory:count", "inventory:export"],
  controls: ["inventory:count", "inventory:receive"],
  packages: ["inventory:read", "inventory:approve"],
  trace: ["inventory:export", "inventory:issue", "inventory:count"]
};

type InventoryMenuItem = Omit<Menu.MenuOptions, "children"> & {
  tab?: string;
  children?: InventoryMenuItem[];
};

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
    meta: { ...meta("进销存驾驶舱", "DataBoard"), isAffix: true }
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/materials`,
    name: "inventorySystemMaterials",
    meta: meta("库存管理", "Box"),
    children: [
      { path: `${INVENTORY_SYSTEM_PREFIX}/stock`, name: "inventorySystemStock", tab: "stock", meta: meta("入库与库存", "Goods") },
      { path: `${INVENTORY_SYSTEM_PREFIX}/items`, name: "inventorySystemItems", tab: "items", meta: meta("物资档案", "Document") }
    ]
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/operations`,
    name: "inventorySystemOperations",
    meta: meta("申领与计划", "Tickets"),
    children: [
      { path: `${INVENTORY_SYSTEM_PREFIX}/requests`, name: "inventorySystemRequests", tab: "requests", meta: meta("申领与签收", "List") },
      { path: `${INVENTORY_SYSTEM_PREFIX}/weekly`, name: "inventorySystemWeekly", tab: "weekly", meta: meta("周用量核对", "Calendar") }
    ]
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/governance`,
    name: "inventorySystemGovernance",
    meta: meta("耗材治理", "SetUp"),
    children: [
      { path: `${INVENTORY_SYSTEM_PREFIX}/packages`, name: "inventorySystemPackages", tab: "packages", meta: meta("患者耗材套餐", "Collection") }
    ]
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/audit`,
    name: "inventorySystemAudit",
    meta: meta("盘点与追溯", "Checked"),
    children: [
      { path: `${INVENTORY_SYSTEM_PREFIX}/controls`, name: "inventorySystemControls", tab: "controls", meta: meta("盘点与报损", "EditPen") },
      { path: `${INVENTORY_SYSTEM_PREFIX}/trace`, name: "inventorySystemTrace", tab: "trace", meta: meta("出入库记录", "TrendCharts") }
    ]
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/analytics`,
    name: "inventorySystemAnalytics",
    meta: meta("统计与分析", "PieChart"),
    children: [
      { path: `${INVENTORY_SYSTEM_PREFIX}/executive`, name: "inventorySystemExecutive", tab: "executive", meta: meta("管理看板", "DataAnalysis") }
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

export const hasInventorySystemAccess = (buttonPermissions: Record<string, string[]>, capabilities: string[]) =>
  capabilities.some(code => code.startsWith("inventory:")) || Object.keys(buttonPermissions).some(name => name.startsWith("inventory"));

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
    meta: { ...meta("进销存驾驶舱", "DataBoard"), isAffix: true }
  },
  { path: `${INVENTORY_SYSTEM_PREFIX}/materials`, name: "inventorySystemMaterials", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/stock`), meta: meta("库存管理", "Box") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/operations`, name: "inventorySystemOperations", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/requests`), meta: meta("申领与计划", "Tickets") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/governance`, name: "inventorySystemGovernance", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/packages`), meta: meta("耗材治理", "SetUp") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/audit`, name: "inventorySystemAudit", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/controls`), meta: meta("盘点与追溯", "Checked") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/analytics`, name: "inventorySystemAnalytics", redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/executive`), meta: meta("统计与分析", "PieChart") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/stock`, name: "inventorySystemStock", component: inventoryView, meta: meta("入库与库存", "Goods") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/items`, name: "inventorySystemItems", component: inventoryView, meta: meta("物资档案", "Document") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/requests`, name: "inventorySystemRequests", component: inventoryView, meta: meta("申领与签收", "List") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/weekly`, name: "inventorySystemWeekly", component: inventoryView, meta: meta("周用量核对", "Calendar") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/packages`, name: "inventorySystemPackages", component: inventoryView, meta: meta("患者耗材套餐", "Collection") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/controls`, name: "inventorySystemControls", component: inventoryView, meta: meta("盘点与报损", "EditPen") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/trace`, name: "inventorySystemTrace", component: inventoryView, meta: meta("出入库记录", "TrendCharts") },
  { path: `${INVENTORY_SYSTEM_PREFIX}/executive`, name: "inventorySystemExecutive", component: inventoryView, meta: meta("管理看板", "DataAnalysis") }
];
