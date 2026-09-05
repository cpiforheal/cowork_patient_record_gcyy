import { createApp } from "vue";
import App from "./App.vue";
// reset style sheet
import "@/styles/reset.scss";
// CSS common style sheet
import "@/styles/common.scss";
// iconfont css
import "@/assets/iconfont/iconfont.scss";
// element dark css
import "element-plus/theme-chalk/dark/css-vars.css";
// custom element dark css
import "@/styles/element-dark.scss";
// custom element css
import "@/styles/element.scss";
// inspira 动效基建（Tailwind v4，无 preflight，不影响 Element Plus）
import "@/styles/tailwind.css";
// element plus service styles and directives
import { registerElementPlusServices } from "@/plugins/elementPlusServices";
// element icons used by dynamic menus
import { registerElementIcons } from "@/plugins/elementIcons";
// custom directives
import directives from "@/directives/index";
// vue Router
import router from "@/routers";
// pinia store
import pinia from "@/stores";
// errorHandler
import errorHandler from "@/utils/errorHandler";

const app = createApp(App);

app.config.errorHandler = errorHandler;

registerElementIcons(app);
registerElementPlusServices(app);

app.use(directives).use(router).use(pinia).mount("#app");
