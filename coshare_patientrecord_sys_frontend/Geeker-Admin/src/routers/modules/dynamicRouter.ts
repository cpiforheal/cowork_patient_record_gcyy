import router from "@/routers/index";
import { RouteRecordRaw } from "vue-router";
import { ElNotification } from "element-plus";
import { useAuthStore } from "@/stores/modules/auth";
import { notFoundRouter } from "@/routers/modules/staticRouter";

// 引入 views 文件夹下所有 vue 文件
const modules = import.meta.glob("@/views/**/*.vue");
const NOT_FOUND_ROUTE_NAME = "notFound";

const resolveViewComponent = (componentPath: string) => {
  const normalizedPath = componentPath.startsWith("/") ? componentPath : `/${componentPath}`;
  const modulePath = `/src/views${normalizedPath}.vue`;
  const component = modules[modulePath];
  if (!component) throw new Error(`动态路由组件不存在：${modulePath}`);
  return component;
};

const createDynamicRoute = (menuItem: Menu.MenuOptions): RouteRecordRaw => {
  const route = {
    ...menuItem,
    meta: { ...menuItem.meta }
  } as unknown as RouteRecordRaw;

  delete route.children;
  if (typeof menuItem.component === "string") route.component = resolveViewComponent(menuItem.component);
  return route;
};

const ensureNotFoundRoute = () => {
  if (!router.hasRoute(NOT_FOUND_ROUTE_NAME)) router.addRoute(notFoundRouter);
};

/**
 * @description 初始化动态路由。只负责加载权限并注册路由，不在内部执行页面跳转。
 */
export const initDynamicRouter = async () => {
  const authStore = useAuthStore();

  try {
    await Promise.all([authStore.getAuthMenuList(), authStore.getAuthButtonList()]);

    if (!authStore.authMenuListGet.length) {
      ElNotification({
        title: "无权限访问",
        message: "当前账号无任何菜单权限，请联系系统管理员！",
        type: "warning",
        duration: 3000
      });
      throw new Error("当前账号无任何菜单权限");
    }

    const registeredPaths = new Set<string>();
    authStore.flatMenuListGet.forEach(item => {
      if (!item.name) throw new Error(`动态路由缺少名称：${item.path}`);
      if (registeredPaths.has(item.path)) throw new Error(`动态路由路径重复：${item.path}`);
      registeredPaths.add(item.path);
      if (router.hasRoute(item.name)) return;

      const route = createDynamicRoute(item);
      if (item.meta?.isFull) router.addRoute(route);
      else router.addRoute("layout", route);
    });

    ensureNotFoundRoute();
  } catch (error) {
    ensureNotFoundRoute();
    throw error instanceof Error ? error : new Error(String(error));
  }
};
