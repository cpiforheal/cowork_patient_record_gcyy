import type { App, Component } from "vue";
import {
  Box,
  Checked,
  Collection,
  CollectionTag,
  Connection,
  DataAnalysis,
  DataLine,
  Document,
  DocumentAdd,
  DocumentChecked,
  DocumentCopy,
  EditPen,
  Expand,
  Files,
  FirstAidKit,
  Fold,
  FolderOpened,
  Goods,
  Guide,
  HomeFilled,
  Link,
  List,
  Lock,
  Menu,
  Memo,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  RefreshLeft,
  Search,
  SetUp,
  Setting,
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
  CollectionTag,
  Connection,
  DataAnalysis,
  DataLine,
  Document,
  DocumentAdd,
  DocumentChecked,
  DocumentCopy,
  EditPen,
  Expand,
  Files,
  FirstAidKit,
  Fold,
  FolderOpened,
  Goods,
  Guide,
  HomeFilled,
  Link,
  List,
  Lock,
  Menu,
  Memo,
  Monitor,
  Notebook,
  OfficeBuilding,
  Operation,
  RefreshLeft,
  Search,
  SetUp,
  Setting,
  Tickets,
  Tools,
  TrendCharts,
  Upload,
  UploadFilled,
  User,
  UserFilled,
  MedicineBox: FirstAidKit
};

const dynamicIconsByLowercase = Object.fromEntries(
  Object.entries(dynamicIcons).map(([name, component]) => [name.toLowerCase(), component])
) as Record<string, Component>;

export const resolveMenuIcon = (name?: string): Component =>
  (name ? (dynamicIcons[name] ?? dynamicIconsByLowercase[name.toLowerCase()]) : undefined) ?? Menu;

export const registerElementIcons = (app: App) => {
  Object.entries(dynamicIcons).forEach(([name, component]) => {
    app.component(name, component);
    app.component(name.toLowerCase(), component);
  });
};
