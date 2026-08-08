import { createRouter, createWebHashHistory, createWebHistory, RouteLocationNormalized } from "vue-router";
import { useUserStore } from "@/stores/modules/user";
import { useAuthStore } from "@/stores/modules/auth";
import { HOME_URL, LOGIN_URL, ROUTER_WHITE_LIST } from "@/config";
import { initDynamicRouter } from "@/routers/modules/dynamicRouter";
import { staticRouter, errorRouter } from "@/routers/modules/staticRouter";
import {
  INVENTORY_SYSTEM_DASHBOARD,
  canAccessInventoryTab,
  inventorySystemPathForLegacy,
  inventoryTabFromPath,
  isInventorySystemPath,
  isLegacyInventoryPath
} from "@/routers/modules/inventorySystem";
import NProgress from "@/config/nprogress";

const mode = import.meta.env.VITE_ROUTER_MODE;

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
  /^\/templates\/ai-document\/?$/,
  /^\/audit(?:\/review|\/log)?\/?$/,
  /^\/documents(?:\/recycle)?\/?$/
];

const unavailableRouteFor = (path: string) => (PROTECTED_BUSINESS_PATHS.some(pattern => pattern.test(path)) ? "/403" : "/404");

const systemLandingFor = (path: string, authStore: ReturnType<typeof useAuthStore>) => {
  if (path !== HOME_URL) return path;
  // Always land on the system boundary. The page renders only the systems
  // allowed by the account, while the backend remains the authority for access.
  return "/system-select";
};

/**
 * @description 📚 路由参数配置简介
 * @param path ==> 路由菜单访问路径
 * @param name ==> 路由 name (对应页面组件 name, 可用作 KeepAlive 缓存标识 && 按钮权限筛选)
 * @param redirect ==> 路由重定向地址
 * @param component ==> 视图文件路径
 * @param meta ==> 路由菜单元信息
 * @param meta.icon ==> 菜单和面包屑对应的图标
 * @param meta.title ==> 路由标题 (用作 document.title || 菜单的名称)
 * @param meta.activeMenu ==> 当前路由为详情页时，需要高亮的菜单
 * @param meta.isLink ==> 路由外链时填写的访问地址
 * @param meta.isHide ==> 是否在菜单中隐藏 (通常列表详情页需要隐藏)
 * @param meta.isFull ==> 菜单是否全屏 (示例：数据大屏页面)
 * @param meta.isAffix ==> 菜单是否固定在标签页中 (首页通常是固定项)
 * @param meta.isKeepAlive ==> 当前路由是否缓存
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
 * @description 路由拦截 beforeEach
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
      const targetPath = systemLandingFor(to.path, authStore);
      const resolvedTarget = router.resolve(targetPath === to.path ? to.fullPath : targetPath);
      if (!resolvedTarget.matched.length || resolvedTarget.name === "notFound") {
        return { path: unavailableRouteFor(to.path), replace: true };
      }
      return { path: targetPath === to.path ? to.fullPath : targetPath, replace: true };
    }

    if (to.name === "notFound") return { path: unavailableRouteFor(to.path), replace: true };
    if (isInventorySystemPath(to.path)) {
      if (!authStore.hasInventorySystemAccessGet) return { path: "/403", replace: true };
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
    console.error("动态路由初始化失败", error);
    userStore.setToken("");
    resetRouter();
    return { path: LOGIN_URL, query: { redirect: to.fullPath }, replace: true };
  }
});

/**
 * @description 重置路由
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
 * @description 路由跳转错误
 * */
router.onError(error => {
  NProgress.done();
  console.warn("路由错误", error.message);
});

/**
 * @description 路由跳转结束
 * */
router.afterEach(() => {
  NProgress.done();
});

export default router;
