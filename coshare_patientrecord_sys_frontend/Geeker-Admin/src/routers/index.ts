import { createRouter, createWebHashHistory, createWebHistory, RouteLocationNormalized } from "vue-router";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import { HOME_URL, LOGIN_URL, ROUTER_WHITE_LIST } from "@/config";
import { initDynamicRouter } from "@/routers/modules/dynamicRouter";
import { staticRouter, errorRouter } from "@/routers/modules/staticRouter";
import {
  canAccessInventoryTab,
  inventorySystemPathForLegacy,
  inventoryTabFromPath,
  isInventorySystemPath,
  isLegacyInventoryPath
} from "@/routers/modules/inventorySystem";
import NProgress from "@/config/nprogress";

const mode = import.meta.env.VITE_ROUTER_MODE;
const isInventoryPortal = import.meta.env.VITE_PORTAL_MODE === "inventory";

const routerMode = {
  hash: () => createWebHashHistory(),
  history: () => createWebHistory()
};
const createHistory = routerMode[mode as keyof typeof routerMode] ?? routerMode.hash;
const PROTECTED_BUSINESS_PATHS = [
  /^\/navigation\/(patient-collaboration|materials-documents|quality-audit)\/?$/,
  /^\/pre-ai(?:\/encounters)?\/?$/,
  /^\/patients(?:\/list|\/detail\/[^/]+)?\/?$/,
  /^\/encounters(?:\/active)?\/?$/,
  /^\/workbench(?:\/upload|\/lab-report|\/legacy)?\/?$/,
  /^\/audit(?:\/review|\/log)?\/?$/,
  /^\/documents(?:\/recycle)?\/?$/
];

const unavailableRouteFor = (path: string) => (PROTECTED_BUSINESS_PATHS.some(pattern => pattern.test(path)) ? "/403" : "/404");

const systemLandingFor = (path: string) => {
  if (path !== HOME_URL) return path;
  // Always land on the system boundary. The page renders only the systems
  // allowed by the account, while the backend remains the authority for access.
  return "/system-select";
};

/**
 * @description 馃摎 璺敱鍙傛暟閰嶇疆绠€浠? * @param path ==> 璺敱鑿滃崟璁块棶璺緞
 * @param name ==> 璺敱 name (瀵瑰簲椤甸潰缁勪欢 name, 鍙敤浣?KeepAlive 缂撳瓨鏍囪瘑 && 鎸夐挳鏉冮檺绛涢€?
 * @param redirect ==> 璺敱閲嶅畾鍚戝湴鍧€
 * @param component ==> 瑙嗗浘鏂囦欢璺緞
 * @param meta ==> 璺敱鑿滃崟鍏冧俊鎭? * @param meta.icon ==> 鑿滃崟鍜岄潰鍖呭睉瀵瑰簲鐨勫浘鏍? * @param meta.title ==> 璺敱鏍囬 (鐢ㄤ綔 document.title || 鑿滃崟鐨勫悕绉?
 * @param meta.activeMenu ==> 褰撳墠璺敱涓鸿鎯呴〉鏃讹紝闇€瑕侀珮浜殑鑿滃崟
 * @param meta.isLink ==> 璺敱澶栭摼鏃跺～鍐欑殑璁块棶鍦板潃
 * @param meta.isHide ==> 鏄惁鍦ㄨ彍鍗曚腑闅愯棌 (閫氬父鍒楄〃璇︽儏椤甸渶瑕侀殣钘?
 * @param meta.isFull ==> 鑿滃崟鏄惁鍏ㄥ睆 (绀轰緥锛氭暟鎹ぇ灞忛〉闈?
 * @param meta.isAffix ==> 鑿滃崟鏄惁鍥哄畾鍦ㄦ爣绛鹃〉涓?(棣栭〉閫氬父鏄浐瀹氶」)
 * @param meta.isKeepAlive ==> 褰撳墠璺敱鏄惁缂撳瓨
 * */
const router = createRouter({
  history: createHistory(),
  routes: [...staticRouter, ...errorRouter],
  strict: false,
  scrollBehavior: () => ({ left: 0, top: 0 })
});

const setDocumentTitle = (to: RouteLocationNormalized) => {
  const title = import.meta.env.VITE_GLOB_APP_TITLE;
  document.title = to.meta.title ? `${to.meta.title} - ${title}` : title;
};

/**
 * @description 璺敱鎷︽埅 beforeEach
 */
router.beforeEach(async to => {
  const userStore = useUserStore();
  const authStore = useAuthStore();

  NProgress.start();
  setDocumentTitle(to);

  if (to.path.toLocaleLowerCase() === LOGIN_URL) {
    if (userStore.token) return { path: HOME_URL, replace: true };
    resetRouter();
    return true;
  }

  if (ROUTER_WHITE_LIST.includes(to.path)) return true;
  if (!userStore.token) return { path: LOGIN_URL, query: { redirect: to.fullPath }, replace: true };
  if (isLegacyInventoryPath(to.path)) return { path: inventorySystemPathForLegacy(to.path), replace: true };

  try {
    if (!authStore.authMenuListGet.length) {
      await initDynamicRouter();
      const targetPath = systemLandingFor(to.path);
      const resolvedTarget = router.resolve(targetPath === to.path ? to.fullPath : targetPath);
      if (!resolvedTarget.matched.length || resolvedTarget.name === "notFound") {
        return { path: unavailableRouteFor(to.path), replace: true };
      }
      return { path: targetPath === to.path ? to.fullPath : targetPath, replace: true };
    }

    if (to.name === "notFound") return { path: unavailableRouteFor(to.path), replace: true };
    if (isInventorySystemPath(to.path)) {
      if (!authStore.hasInventorySystemAccessGet) return { path: "/403", replace: true };
      if (isInventoryPortal) {
        const isAdministrator = (authStore.capabilities || []).includes("inventory:role:manage");
        const departmentPath = "/inventory-system/departments/" + (authStore.activeDepartment?.id || "");
        const portalLanding = isAdministrator ? "/inventory-system/daily-verification" : departmentPath;
        const isMessageBoard = to.path === "/inventory-system/message-board";
        const isDepartmentMaterials = to.path === "/inventory-system/department-materials";
        const isPackageRoute = to.path === "/inventory-system/packages" || isDepartmentMaterials;
        const hasPackageAccess = canAccessInventoryTab("packages", authStore.authButtonListGet, authStore.capabilities || []);
        const allowed = isAdministrator
          ? isMessageBoard ||
            (isPackageRoute && hasPackageAccess) ||
            to.path === "/inventory-system/daily-verification" ||
            to.path === "/inventory-system/role-management" ||
            to.path.startsWith("/inventory-system/departments/")
          : isMessageBoard || to.path === departmentPath || (isPackageRoute && hasPackageAccess);
        if (!allowed) return { path: portalLanding, replace: true };
      }
      const tab = inventoryTabFromPath(to.path);
      if (tab && !canAccessInventoryTab(tab, authStore.authButtonListGet, authStore.capabilities || [])) {
        return { path: "/403", replace: true };
      }
      authStore.setActiveSystem("inventory");
    } else if (to.path !== "/system-select") {
      authStore.setActiveSystem("medical");
    }
    await authStore.setRouteName(String(to.name || ""));
    return true;
  } catch (error) {
    console.error("鍔ㄦ€佽矾鐢卞垵濮嬪寲澶辫触", error);
    userStore.setToken("");
    resetRouter();
    return { path: LOGIN_URL, query: { redirect: to.fullPath }, replace: true };
  }
});

/**
 * @description 閲嶇疆璺敱
 * */
export const resetRouter = () => {
  const authStore = useAuthStore();
  const staticRouteNames = new Set(["login", "layout", "welcome", "home", "systemSelect", "403", "404", "500"]);
  authStore.flatMenuListGet.forEach(route => {
    const { name } = route;
    if (name && !staticRouteNames.has(String(name)) && router.hasRoute(name)) router.removeRoute(name);
  });
  if (router.hasRoute("notFound")) router.removeRoute("notFound");
  authStore.authMenuList = [];
  authStore.authButtonList = {};
  authStore.routeName = "";
  authStore.activeSystem = "medical";
};

/**
 * @description 璺敱璺宠浆閿欒
 * */
router.onError(error => {
  NProgress.done();
  console.warn("璺敱閿欒", error.message);
});

/**
 * @description 璺敱璺宠浆缁撴潫
 * */
router.afterEach(() => {
  NProgress.done();
});

export default router;
