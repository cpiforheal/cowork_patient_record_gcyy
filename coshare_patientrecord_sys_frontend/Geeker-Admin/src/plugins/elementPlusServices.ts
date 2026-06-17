import type { App } from "vue";
import { ElLoading } from "element-plus";

import "element-plus/es/components/loading/style/css";
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/message-box/style/css";
import "element-plus/es/components/notification/style/css";

export const registerElementPlusServices = (app: App) => {
  app.use(ElLoading);
};
