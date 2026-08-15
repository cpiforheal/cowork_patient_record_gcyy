import { RouteRecordRaw } from "vue-router";
import { getShowMenuList } from "@/utils";

export const INVENTORY_SYSTEM_PREFIX = "/inventory-system";
export const INVENTORY_SYSTEM_DASHBOARD = `${INVENTORY_SYSTEM_PREFIX}/dashboard`;

export const INVENTORY_TAB_ROUTE_NAMES: Record<string, string> = {
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

export const INVENTORY_TAB_CAPABILITIES: Record<string, readonly string[]> = {
  overview: [
    "inventory:read",
    "inventory:request",
    "inventory:receive",
    "inventory:approve",
    "inventory:issue",
    "inventory:count",
    "inventory:export"
  ],
  executive: ["inventory:read", "inventory:report", "inventory:approve", "inventory:issue", "inventory:count"],
  requests: ["inventory:read", "inventory:request", "inventory:approve", "inventory:issue", "inventory:receive"],
  stock: ["inventory:read", "inventory:issue", "inventory:export", "inventory:item:manage"],
  items: ["inventory:read", "inventory:item:manage", "inventory:rule"],
  weekly: ["inventory:read", "inventory:request", "inventory:approve", "inventory:count", "inventory:export"],
  controls: ["inventory:read", "inventory:count", "inventory:receive"],
  packages: ["inventory:read", "inventory:approve", "inventory:rule"],
  trace: ["inventory:read", "inventory:export", "inventory:issue", "inventory:count"],
  daily: ["inventory:read"],
  roles: ["inventory:role:manage"],
  messageBoard: ["inventory:read"]
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

export const canAccessInventoryTab = (tab: string, buttonPermissions: Record<string, string[]>, capabilities: string[]) => {
  const required = INVENTORY_TAB_CAPABILITIES[tab] || [];
  const routePermissions = buttonPermissions[INVENTORY_TAB_ROUTE_NAMES[tab]] || [];
  const granted = new Set(routePermissions.length ? routePermissions : capabilities);
  return required.some(code => granted.has(code));
};

export const getInventorySystemMenu = (
  buttonPermissions: Record<string, string[]>,
  capabilities: string[],
  departmentKey?: string
): Menu.MenuOptions[] => {
  const granted = new Set([...capabilities, ...Object.values(buttonPermissions).flat()]);
  if (granted.has("inventory:role:manage")) {
    return [
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/daily-verification`,
        name: "inventorySystemDailyVerification",
        meta: meta("12科室日报汇总", "Tickets")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/department-materials`,
        name: "inventorySystemDepartmentMaterials",
        meta: meta("科室套餐", "Collection")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/role-management`,
        name: "inventorySystemRoleManagement",
        meta: meta("账号管理", "UserFilled")
      },
      {
        path: `${INVENTORY_SYSTEM_PREFIX}/message-board`,
        name: "inventorySystemMessageBoard",
        meta: meta("需求留言板", "ChatDotRound")
      }
    ];
  }
  if (!departmentKey) return [];
  const department = departmentEntries.find(entry => entry.key === departmentKey);
  return [
    {
      path: `${INVENTORY_SYSTEM_PREFIX}/departments/${departmentKey}`,
      name: "inventorySystemPortalDepartment",
      meta: meta(department ? `我的科室日报（${department.title}）` : "我的科室日报", "EditPen")
    },
    {
      path: `${INVENTORY_SYSTEM_PREFIX}/message-board`,
      name: "inventorySystemMessageBoard",
      meta: meta("需求留言板", "ChatDotRound")
    }
  ];
};

const filterMedicalMenu = (items: Menu.MenuOptions[]): Menu.MenuOptions[] =>
  items.flatMap(item => {
    if (isLegacyInventoryPath(item.path)) return [];
    if (!item.children?.length) return [item];

    const children = filterMedicalMenu(item.children);
    return children.length ? [{ ...item, children }] : [];
  });

export const getMedicalSystemMenu = (items: Menu.MenuOptions[]) => getShowMenuList(filterMedicalMenu(items));

// 入口由进销存能力决定；管理设置仍会在菜单层按对应权限单独过滤。
export const hasInventorySystemAccess = (buttonPermissions: Record<string, string[]>, capabilities: string[]) =>
  capabilities.some(code => code.startsWith("inventory:")) ||
  Object.values(buttonPermissions).some(permissions => permissions.some(code => code.startsWith("inventory:")));

export const hasMedicalSystemAccess = (menus: Menu.MenuOptions[]) => getMedicalSystemMenu(menus).length > 0;

export const isInventorySystemPath = (path: string) =>
  path === INVENTORY_SYSTEM_PREFIX || path.startsWith(`${INVENTORY_SYSTEM_PREFIX}/`);

export const inventoryTabFromPath = (path: string) => {
  const paths: Record<string, string> = {
    "/inventory-system/dashboard": "overview",
    "/inventory-system/executive": "executive",
    "/inventory-system/requests": "requests",
    "/inventory-system/stock": "stock",
    "/inventory-system/items": "items",
    "/inventory-system/weekly": "weekly",
    "/inventory-system/controls": "controls",
    "/inventory-system/packages": "packages",
    "/inventory-system/department-materials": "packages",
    "/inventory-system/consumable-entry": "packages",
    "/inventory-system/trace": "trace",
    "/inventory-system/daily-verification": "daily",
    "/inventory-system/role-management": "roles"
  };
  return paths[path] || (path.startsWith("/inventory-system/departments/") ? "packages" : "");
};

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
    meta: meta("库存工作台", "DataBoard")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/materials`,
    name: "inventorySystemMaterials",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/stock`),
    meta: meta("库存管理", "Box")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/operations`,
    name: "inventorySystemOperations",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/requests`),
    meta: meta("科室申领", "Tickets")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/governance`,
    name: "inventorySystemGovernance",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/department-materials`),
    meta: meta("患者耗材规则", "SetUp")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/departments`,
    name: "inventorySystemDepartments",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/consumable-entry`),
    meta: meta("科室耗材", "OfficeBuilding")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/audit`,
    name: "inventorySystemAudit",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/daily-verification`),
    meta: meta("对账与追溯", "Checked")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/analytics`,
    name: "inventorySystemAnalytics",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/executive`),
    meta: meta("管理设置", "PieChart")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/stock`,
    name: "inventorySystemStock",
    component: inventoryView,
    meta: meta("库存总览与入库", "Goods")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/items`,
    name: "inventorySystemItems",
    component: inventoryView,
    meta: meta("物资目录", "Document")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/requests`,
    name: "inventorySystemRequests",
    component: inventoryView,
    meta: meta("科室申领", "List")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/weekly`,
    name: "inventorySystemWeekly",
    component: inventoryView,
    meta: meta("周用量核对", "Calendar")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/consumable-entry`,
    name: "inventorySystemConsumableEntry",
    component: inventoryView,
    meta: meta("我的科室耗材", "EditPen")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/packages`,
    name: "inventorySystemPackages",
    redirect: redirectTo(`${INVENTORY_SYSTEM_PREFIX}/department-materials`),
    meta: meta("科室套餐", "Collection")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/department-materials`,
    name: "inventorySystemDepartmentMaterials",
    component: inventoryView,
    meta: meta("科室套餐", "Collection")
  },
  ...departmentEntries.map(entry => ({
    path: `${INVENTORY_SYSTEM_PREFIX}/departments/${entry.key}`,
    name: `inventorySystemDepartment${entry.key.replace(/(^|-)([a-z])/g, (_, _dash, letter) => letter.toUpperCase())}`,
    component: inventoryView,
    meta: meta(entry.title, entry.icon)
  })),
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/controls`,
    name: "inventorySystemControls",
    component: inventoryView,
    meta: meta("盘点与报损", "EditPen")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/trace`,
    name: "inventorySystemTrace",
    component: inventoryView,
    meta: meta("出入库追溯", "TrendCharts")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/executive`,
    name: "inventorySystemExecutive",
    component: inventoryView,
    meta: meta("运行总览", "DataAnalysis")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/daily-verification`,
    name: "inventorySystemDailyVerification",
    component: inventoryView,
    meta: meta("每日耗材核对", "Tickets")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/role-management`,
    name: "inventorySystemRoleManagement",
    component: inventoryView,
    meta: meta("岗位与权限", "UserFilled")
  },
  {
    path: `${INVENTORY_SYSTEM_PREFIX}/message-board`,
    name: "inventorySystemMessageBoard",
    component: inventoryView,
    meta: meta("需求留言板", "ChatDotRound")
  }
];
