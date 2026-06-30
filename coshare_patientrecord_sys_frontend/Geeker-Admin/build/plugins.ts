import type { PluginOption } from "vite";
import vue from "@vitejs/plugin-vue";
import vueJsx from "@vitejs/plugin-vue-jsx";
import eslintPlugin from "vite-plugin-eslint";
import { createHtmlPlugin } from "vite-plugin-html";
import viteCompression from "vite-plugin-compression";
import vueSetupExtend from "unplugin-vue-setup-extend-plus/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

export const createVitePlugins = (viteEnv: ViteEnv, command: "build" | "serve"): (PluginOption | PluginOption[])[] => {
  const { VITE_GLOB_APP_TITLE } = viteEnv;

  return [
    vue(),
    vueJsx(),
    command === "serve" ? eslintPlugin() : null,
    vueSetupExtend({}),
    Components({
      dts: false,
      resolvers: [
        ElementPlusResolver({
          importStyle: "css"
        })
      ]
    }),
    createCompression(viteEnv),
    createHtmlPlugin({
      minify: true,
      inject: {
        data: { title: VITE_GLOB_APP_TITLE }
      }
    })
  ];
};

const createCompression = (viteEnv: ViteEnv): PluginOption[] => {
  const { VITE_BUILD_COMPRESS = "none", VITE_BUILD_COMPRESS_DELETE_ORIGIN_FILE } = viteEnv;
  const compressList = VITE_BUILD_COMPRESS.split(",");
  const plugins: PluginOption[] = [];

  if (compressList.includes("gzip")) {
    plugins.push(
      viteCompression({
        ext: ".gz",
        algorithm: "gzip",
        deleteOriginFile: VITE_BUILD_COMPRESS_DELETE_ORIGIN_FILE
      })
    );
  }

  if (compressList.includes("brotli")) {
    plugins.push(
      viteCompression({
        ext: ".br",
        algorithm: "brotliCompress",
        deleteOriginFile: VITE_BUILD_COMPRESS_DELETE_ORIGIN_FILE
      })
    );
  }

  return plugins;
};
