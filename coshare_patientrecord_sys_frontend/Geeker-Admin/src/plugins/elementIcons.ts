import type { App, Component } from "vue";
import {
  Box,
  Checked,
  Collection,
  Connection,
  DataLine,
  Document,
  DocumentChecked,
  DocumentCopy,
  Expand,
  Files,
  Fold,
  FolderOpened,
  Goods,
  Guide,
  HomeFilled,
  List,
  Lock,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  RefreshLeft,
  Tickets,
  Tools,
  TrendCharts,
  Upload,
  UploadFilled,
  User,
  UserFilled
} from "@element-plus/icons-vue";

const dynamicIcons: Record<string, Component> = {
  Box,
  Checked,
  Collection,
  Connection,
  DataLine,
  Document,
  DocumentChecked,
  DocumentCopy,
  Expand,
  Files,
  Fold,
  FolderOpened,
  Goods,
  Guide,
  HomeFilled,
  List,
  Lock,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  RefreshLeft,
  Tickets,
  Tools,
  TrendCharts,
  Upload,
  UploadFilled,
  User,
  UserFilled
};

export const registerElementIcons = (app: App) => {
  Object.entries(dynamicIcons).forEach(([name, component]) => {
    app.component(name, component);
    app.component(name.toLowerCase(), component);
  });
};
