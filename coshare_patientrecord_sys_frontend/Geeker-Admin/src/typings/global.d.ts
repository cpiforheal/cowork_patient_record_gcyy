/* Tailwind v4 Vite 插件：包类型经 exports 暴露，Node10 解析策略读不到，此处环境声明兜底 */
declare module "@tailwindcss/vite" {
  import type { Plugin } from "vite";
  const tailwindcss: () => Plugin;
  export default tailwindcss;
}

/* Menu */
declare namespace Menu {
  interface MenuOptions {
    path: string;
    name: string;
    component?: string | (() => Promise<unknown>);
    redirect?: string;
    meta: MetaProps;
    children?: MenuOptions[];
  }
  interface MetaProps {
    icon: string;
    title: string;
    activeMenu?: string;
    isLink?: string;
    isHide: boolean;
    isFull: boolean;
    isAffix: boolean;
    isKeepAlive: boolean;
    /** 侧边栏分组小标题：同 section 的条目并列渲染在同一小标题下 */
    section?: string | null;
  }
}

/* FileType */
declare namespace File {
  type ImageMimeType =
    | "image/apng"
    | "image/bmp"
    | "image/gif"
    | "image/jpeg"
    | "image/pjpeg"
    | "image/png"
    | "image/svg+xml"
    | "image/tiff"
    | "image/webp"
    | "image/x-icon";

  type ExcelMimeType = "application/vnd.ms-excel" | "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
}

/* Vite */
declare type Recordable<T = any> = Record<string, T>;

declare interface ViteEnv {
  VITE_USER_NODE_ENV: "development" | "production" | "test";
  VITE_PORTAL_MODE?: "medical" | "inventory";
  VITE_GLOB_APP_TITLE: string;
  VITE_PORT: number;
  VITE_OPEN: boolean;
  VITE_ROUTER_MODE: "hash" | "history";
  VITE_BUILD_COMPRESS: "gzip" | "brotli" | "gzip,brotli" | "none";
  VITE_BUILD_COMPRESS_DELETE_ORIGIN_FILE: boolean;
  VITE_DROP_CONSOLE: boolean;
  VITE_PUBLIC_PATH: string;
  VITE_API_URL: string;
  VITE_CLINIC_API_DB_URL?: string;
  VITE_CLINIC_API_FILES_URL?: string;
  VITE_PROXY: [string, string, boolean?][];
}

interface ImportMetaEnv extends ViteEnv {
  __: unknown;
}

/* __APP_INFO__ */
declare const __APP_INFO__: {
  pkg: {
    name: string;
    version: string;
    dependencies: Recordable<string>;
    devDependencies: Recordable<string>;
  };
  lastBuildTime: string;
};
