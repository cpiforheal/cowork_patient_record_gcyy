import { RouteRecordRaw } from "vue-router";
import { HOME_URL, LOGIN_URL } from "@/config";
import { inventorySystemRoutes } from "@/routers/modules/inventorySystem";

/**
 * staticRouter (静态路由)
 */
export const staticRouter: RouteRecordRaw[] = [
  {
    path: "/",
    redirect: HOME_URL
  },
  {
    path: LOGIN_URL,
    name: "login",
    component: () => import("@/views/login/index.vue"),
    meta: {
      title: "登录"
    }
  },
  {
    path: "/layout",
    name: "layout",
    component: () => import("@/layouts/index.vue"),
    redirect: HOME_URL,
    children: [
      {
        path: HOME_URL,
        name: "welcome",
        component: () => import("@/views/welcome/index.vue"),
        meta: {
          icon: "HomeFilled",
          title: "主页",
          isLink: "",
          isHide: false,
          isFull: false,
          isAffix: true,
          isKeepAlive: true
        }
      },
      {
        path: "/home/index",
        name: "home",
        component: () => import("@/views/home/index.vue"),
        meta: {
          icon: "List",
          title: "我的待办",
          isLink: "",
          isHide: false,
          isFull: false,
          isAffix: false,
          isKeepAlive: true
        }
      },
      {
        path: "/system-select",
        name: "systemSelect",
        component: () => import("@/views/system-select/index.vue"),
        meta: {
          icon: "Grid",
          title: "系统选择",
          isLink: "",
          isHide: true,
          isFull: false,
          isAffix: false,
          isKeepAlive: false
        }
      },
      ...inventorySystemRoutes,
      {
        // 病种模板库：静态注册，避免后端导航会话缓存导致的 404（数据权限由接口层二次校验）
        path: "/pre-ai/template-manage",
        name: "diseaseTemplateManage",
        component: () => import("@/views/preAi/templateManage/index.vue"),
        meta: {
          icon: "Files",
          title: "病种模板库",
          isLink: "",
          isHide: false,
          isFull: false,
          isAffix: false,
          isKeepAlive: true
        }
      },
      {
        // 随访话术模板库：静态注册（同病种模板库惯例），编辑权 nurse/nursing/doctor/admin
        path: "/pre-ai/script-manage",
        name: "followUpScriptManage",
        component: () => import("@/views/preAi/scriptManage/index.vue"),
        meta: {
          icon: "ChatLineSquare",
          title: "随访话术模板库",
          isLink: "",
          isHide: false,
          isFull: false,
          isAffix: false,
          isKeepAlive: true
        }
      },
      {
        // 随访工作台独立页：首页板块的全展开形态；后端导航同步下发（isHide 对齐为可见）
        path: "/follow-up-dashboard",
        name: "followUpDashboard",
        component: () => import("@/views/home/components/FollowUpDashboardPage.vue"),
        meta: {
          icon: "DataLine",
          title: "随访工作台",
          isLink: "",
          isHide: false,
          isFull: false,
          isAffix: false,
          isKeepAlive: true
        }
      }
    ]
  }
];

/**
 * errorRouter (固定错误页面路由)
 * 通配 404 必须在动态路由初始化完成后注册，避免首次直达业务页时被提前吞掉。
 */
export const errorRouter: RouteRecordRaw[] = [
  {
    path: "/403",
    name: "403",
    component: () => import("@/components/ErrorMessage/403.vue"),
    meta: {
      title: "403页面"
    }
  },
  {
    path: "/404",
    name: "404",
    component: () => import("@/components/ErrorMessage/404.vue"),
    meta: {
      title: "404页面"
    }
  },
  {
    path: "/500",
    name: "500",
    component: () => import("@/components/ErrorMessage/500.vue"),
    meta: {
      title: "500页面"
    }
  }
];

export const notFoundRouter: RouteRecordRaw = {
  path: "/:pathMatch(.*)*",
  name: "notFound",
  component: () => import("@/components/ErrorMessage/404.vue"),
  meta: {
    title: "页面不存在"
  }
};
