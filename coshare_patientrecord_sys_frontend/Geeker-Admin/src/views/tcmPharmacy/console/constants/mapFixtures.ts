export interface MapFixture {
  key: string;
  type: "cabinet" | "workbench" | "entrance" | "aisle";
  label: string;
  x: number;
  y: number;
  w?: number;
  h?: number;
}

export const MAP_VIEWBOX = { width: 900, height: 480 };

export const MAP_FIXTURES: MapFixture[] = [
  { key: "cabinet-1", type: "cabinet", label: "柜", x: 272, y: 40, w: 50, h: 80 },
  { key: "cabinet-2", type: "cabinet", label: "柜", x: 580, y: 40, w: 50, h: 80 },
  { key: "workbench", type: "workbench", label: "工作台", x: 390, y: 392, w: 140, h: 58 },
  { key: "aisle", type: "aisle", label: "通道", x: 360, y: 150, w: 190, h: 210 },
  { key: "entrance", type: "entrance", label: "入口", x: 330, y: 432, w: 40, h: 40 }
];
