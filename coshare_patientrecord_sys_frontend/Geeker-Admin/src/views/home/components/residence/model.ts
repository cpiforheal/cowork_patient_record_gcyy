/**
 * 患者居住地模型：地址归类（县内乡镇 / 县外县区 / 村社区）+ 层级树 + 乡镇近似分块几何。
 * 层级：来源大区（固始县 / 县外 / 待核实）→（县外：县区）→ 乡镇/街道 → 村/社区 → 患者。
 */
import { Delaunay } from "d3-delaunay";
import polygonClipping from "polygon-clipping";
import gushiCountyGeo from "@/assets/geo/gushi-county.json";

export interface ResidencePerson {
  key: string;
  name: string;
  gender: string;
  age: string;
  phone: string;
  address: string;
  visitDate: string;
  visitCount: number;
  caseId: string;
  encounterId: string;
  source: "preai" | "billing";
}

export type NodeKind = "root" | "region" | "county" | "township" | "village";

export interface ResidenceNode {
  key: string;
  label: string;
  kind: NodeKind;
  count: number;
  visits: number;
  pending: boolean;
  children: Map<string, ResidenceNode>;
  people: ResidencePerson[];
}

export const REGION_LOCAL = "固始县";
export const REGION_OUTSIDE = "县外";
export const REGION_UNKNOWN = "待核实地址";
const PENDING_TOWNSHIP = "乡镇待核实";
const PENDING_VILLAGE = "村/社区待核实";
const PENDING_COUNTY = "县区待核实";

/** 本院（城区）锚点 */
export const HOSPITAL_COORD: [number, number] = [115.65, 32.17];
/** 乡镇驻地近似经纬度（仅用于分布可视化，非精确行政边界） */
export const TOWNSHIP_COORDS: Record<string, [number, number]> = {
  城区: HOSPITAL_COORD,
  沙河铺: [115.76, 32.18],
  泉河铺: [115.83, 32.22],
  分水亭: [115.83, 32.13],
  蒋集: [115.9, 32.24],
  陈集: [115.95, 32.35],
  观堂: [115.85, 32.38],
  徐集: [116.05, 32.32],
  张广庙: [115.97, 32.18],
  石佛店: [116.02, 32.12],
  柳树店: [115.93, 32.05],
  往流: [115.95, 32.45],
  丰港: [115.75, 32.4],
  洪埠: [115.62, 32.3],
  李店: [115.55, 32.35],
  杨集: [115.48, 32.3],
  胡族铺: [115.45, 32.15],
  马堽集: [115.42, 32.02],
  汪棚: [115.55, 32.05],
  赵岗: [115.65, 32.0],
  草庙集: [115.48, 31.95],
  南大桥: [115.68, 31.98],
  方集: [115.72, 31.9],
  段集: [115.8, 31.85],
  祖师庙: [115.85, 31.78],
  武庙集: [115.92, 31.75],
  张老埠: [115.85, 31.95],
  黎集: [115.95, 31.9],
  陈淋子: [116.05, 31.8],
  三河尖: [115.95, 32.48],
  郭陆滩: [115.55, 31.88]
};

const GUSHI_TOWNSHIPS = Object.keys(TOWNSHIP_COORDS)
  .filter(name => name !== "城区")
  .sort((a, b) => b.length - a.length);
/** 简写/别称/异体字 → 规范名 */
const TOWNSHIP_ALIASES: Record<string, string> = {
  祖师: "祖师庙",
  草庙: "草庙集",
  武庙: "武庙集",
  张广: "张广庙",
  分水: "分水亭",
  马罡: "马堽集",
  马堽: "马堽集",
  马岗: "马堽集",
  郭陆: "郭陆滩",
  胡族: "胡族铺",
  泉河: "泉河铺",
  城郊: "城区"
};
const URBAN_STREETS = ["蓼城", "秀水", "番城"];
const URBAN_HINT = /(城区|城关|县城|产业集聚区|开发区)/;

const VILLAGE_RE = /([\u4e00-\u9fa5\d]{1,10}?(?:村民委员会|村委会|村|社区居委会|社区|居委会|小区|花园|家园))/;
const TOWN_PREFIX_RE = /^(?:镇|乡|街道办事处|街道办|街道|办事处|办)/;

const clean = (raw: string) =>
  String(raw || "")
    .replace(/\s+/g, "")
    .replace(/[，,。;；()（）]/g, "");

const pickVillage = (rest: string) => {
  const match = rest.replace(TOWN_PREFIX_RE, "").match(VILLAGE_RE);
  if (!match) return PENDING_VILLAGE;
  return match[1].replace(/村民委员会$|村委会$/, "村").replace(/社区居委会$/, "社区");
};

interface Segment {
  label: string;
  kind: NodeKind;
}

const localTownship = (address: string): { township: string; rest: string } | null => {
  const street = URBAN_STREETS.find(name => address.includes(name));
  if (street) return { township: "城区", rest: address.slice(address.indexOf(street) + street.length) };
  const township = GUSHI_TOWNSHIPS.find(name => address.includes(name));
  if (township) return { township, rest: address.slice(address.indexOf(township) + township.length) };
  const alias = Object.keys(TOWNSHIP_ALIASES).find(name => address.includes(name));
  if (alias) return { township: TOWNSHIP_ALIASES[alias], rest: address.slice(address.indexOf(alias) + alias.length) };
  const urban = address.match(URBAN_HINT);
  if (urban) return { township: "城区", rest: address.slice((urban.index || 0) + urban[0].length) };
  return null;
};

/** 地址 → 层级路径。只认结构化后缀，不猜测；认不出的那一级明确标"待核实"。 */
export const classifyAddress = (raw: string): Segment[] => {
  const address = clean(raw);
  if (!address) return [{ label: REGION_UNKNOWN, kind: "region" }];

  let rest = address;
  const province = rest.match(/^([\u4e00-\u9fa5]{2,3}(?:省|自治区))/);
  if (province) rest = rest.slice(province[1].length);
  const city = rest.match(/^([\u4e00-\u9fa5]{2,4}市)/);
  if (city) rest = rest.slice(city[1].length);
  const county = rest.match(/^([\u4e00-\u9fa5]{1,5}?(?:县|区|旗))/) || rest.match(/^([\u4e00-\u9fa5]{2,4}市)/);
  const countyName = county?.[1] || "";

  const isLocal = address.includes("固始") || (!countyName && !province && localTownship(address) !== null);
  if (isLocal) {
    const afterCounty = address.includes("固始县") ? address.slice(address.indexOf("固始县") + 3) : address;
    const hit = localTownship(afterCounty);
    return [
      { label: REGION_LOCAL, kind: "region" },
      { label: hit?.township || PENDING_TOWNSHIP, kind: "township" },
      { label: pickVillage(hit ? hit.rest : afterCounty), kind: "village" }
    ];
  }

  // 既不像县内也认不出任何省/市/县级行政区：不臆断为县外，归入待核实
  if (!countyName && !city && !province) return [{ label: REGION_UNKNOWN, kind: "region" }];
  if (countyName) rest = rest.slice(countyName.length);
  const upper = city?.[1].replace(/市$/, "") || province?.[1].replace(/省$|自治区$/, "") || "";
  const countyLabel = countyName
    ? upper
      ? `${upper} · ${countyName}`
      : countyName
    : city?.[1] || province?.[1] || PENDING_COUNTY;
  const town = rest.match(/^([\u4e00-\u9fa5]{1,8}?(?:镇|乡|街道|办事处))/);
  if (town) rest = rest.slice(town[1].length);
  return [
    { label: REGION_OUTSIDE, kind: "region" },
    { label: countyLabel, kind: "county" },
    { label: town?.[1] || PENDING_TOWNSHIP, kind: "township" },
    { label: pickVillage(rest), kind: "village" }
  ];
};

const isPendingLabel = (label: string) => label.includes("待核实");

const newNode = (key: string, label: string, kind: NodeKind): ResidenceNode => ({
  key,
  label,
  kind,
  count: 0,
  visits: 0,
  pending: isPendingLabel(label),
  children: new Map(),
  people: []
});

export const buildResidenceTree = (people: ResidencePerson[]): ResidenceNode => {
  const root = newNode("", "全部来源", "root");
  people.forEach(person => {
    let node = root;
    node.count += 1;
    node.visits += person.visitCount;
    classifyAddress(person.address).forEach(segment => {
      const key = `${node.key}/${segment.label}`;
      let child = node.children.get(key);
      if (!child) {
        child = newNode(key, segment.label, segment.kind);
        node.children.set(key, child);
      }
      child.count += 1;
      child.visits += person.visitCount;
      node = child;
    });
    node.people.push(person);
  });
  return root;
};

/** 子节点排序：人数降序，"待核实"类兜底项固定排在最后。 */
export const sortedChildren = (node: ResidenceNode) =>
  [...node.children.values()].sort(
    (a, b) => Number(a.pending) - Number(b.pending) || b.count - a.count || a.label.localeCompare(b.label, "zh")
  );

export const isLeaf = (node: ResidenceNode) => node.children.size === 0;

// ---------------------------------------------------------------- geometry

export interface TownshipShape {
  name: string;
  d: string;
  /** 驻地投影坐标（标签与气泡锚点） */
  x: number;
  y: number;
}

export interface ResidenceGeometry {
  width: number;
  height: number;
  outline: string;
  townships: TownshipShape[];
  hospital: [number, number];
}

type Ring = number[][];

const MAP_WIDTH = 1000;
const PAD = 24;

const hash32 = (value: string) => {
  let h = 2166136261;
  for (let i = 0; i < value.length; i++) {
    h ^= value.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
};
const mulberry32 = (seed: number) => () => {
  seed |= 0;
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

/** 共享边确定性锯齿化：相邻分块同一条边生成同一路径，不留缝也不重叠。 */
const jaggedCache = new Map<string, Ring>();
const jaggedSegment = (a: number[], b: number[]): Ring => {
  const forward = a[0] < b[0] || (a[0] === b[0] && a[1] < b[1]);
  const [first, second] = forward ? [a, b] : [b, a];
  const key = `${first[0]},${first[1]}->${second[0]},${second[1]}`;
  let jag = jaggedCache.get(key);
  if (!jag) {
    const rand = mulberry32(hash32(key));
    const dx = second[0] - first[0];
    const dy = second[1] - first[1];
    const len = Math.hypot(dx, dy) || 1e-9;
    const amp = Math.min(0.018, len * 0.12);
    jag = [];
    for (let s = 1; s <= 4; s++) {
      const t = s / 5;
      const disp = (rand() - 0.5) * 2 * amp;
      jag.push([first[0] + dx * t + (-dy / len) * disp, first[1] + dy * t + (dx / len) * disp]);
    }
    jaggedCache.set(key, jag);
  }
  return forward ? jag : [...jag].reverse();
};

let geometryCache: ResidenceGeometry | null = null;

/** 以乡镇驻地为种子做 Voronoi，再用固始县真实轮廓裁剪，得到近似乡镇分块（无官方乡镇边界数据）。 */
export const residenceGeometry = (): ResidenceGeometry | null => {
  if (geometryCache) return geometryCache;
  const feature = (gushiCountyGeo as { features?: Array<{ geometry: { coordinates: number[][][][] } }> }).features?.[0];
  if (!feature) return null;
  const county = feature.geometry.coordinates;
  const flat = county.flat(2);
  const minLon = Math.min(...flat.map(p => p[0]));
  const maxLon = Math.max(...flat.map(p => p[0]));
  const minLat = Math.min(...flat.map(p => p[1]));
  const maxLat = Math.max(...flat.map(p => p[1]));
  const kx = Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180);
  const scale = (MAP_WIDTH - PAD * 2) / ((maxLon - minLon) * kx);
  const height = Math.round((maxLat - minLat) * scale + PAD * 2);
  const project = (p: number[]): [number, number] => [
    +(PAD + (p[0] - minLon) * kx * scale).toFixed(1),
    +(PAD + (maxLat - p[1]) * scale).toFixed(1)
  ];
  const toPath = (polygons: number[][][][]) =>
    polygons.map(polygon => polygon.map(ring => `M${ring.map(p => project(p).join(",")).join("L")}Z`).join("")).join("");

  // 驻地坐标为近似值，落在县界外的种子沿县城方向内收到界内，保证每个乡镇都有分块
  const rings = county.flat(1);
  const inside = (p: number[]) => {
    let hit = false;
    rings.forEach(ring => {
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const [xi, yi] = ring[i];
        const [xj, yj] = ring[j];
        if (yi > p[1] !== yj > p[1] && p[0] < ((xj - xi) * (p[1] - yi)) / (yj - yi) + xi) hit = !hit;
      }
    });
    return hit;
  };
  const pullIn = (p: [number, number]): [number, number] => {
    if (inside(p)) return p;
    for (let t = 0.05; t <= 1; t += 0.05) {
      const q: [number, number] = [p[0] + (HOSPITAL_COORD[0] - p[0]) * t, p[1] + (HOSPITAL_COORD[1] - p[1]) * t];
      if (inside(q))
        return [
          p[0] + (HOSPITAL_COORD[0] - p[0]) * Math.min(1, t + 0.06),
          p[1] + (HOSPITAL_COORD[1] - p[1]) * Math.min(1, t + 0.06)
        ];
    }
    return HOSPITAL_COORD;
  };
  const seeds = Object.entries(TOWNSHIP_COORDS).map(([name, coord]) => [name, pullIn(coord)] as [string, [number, number]]);
  // 轻量排斥：内收后挤在一起的种子互相推开，避免出现过小分块和标签重叠
  const MIN_GAP = 0.05;
  for (let round = 0; round < 30; round++) {
    let moved = false;
    for (let i = 0; i < seeds.length; i++) {
      for (let j = i + 1; j < seeds.length; j++) {
        const a = seeds[i][1];
        const b = seeds[j][1];
        const dx = b[0] - a[0];
        const dy = b[1] - a[1];
        const dist = Math.hypot(dx, dy) || 1e-6;
        if (dist >= MIN_GAP) continue;
        const push = (MIN_GAP - dist) / 2;
        const na: [number, number] = [a[0] - (dx / dist) * push, a[1] - (dy / dist) * push];
        const nb: [number, number] = [b[0] + (dx / dist) * push, b[1] + (dy / dist) * push];
        if (inside(na)) seeds[i][1] = na;
        if (inside(nb)) seeds[j][1] = nb;
        moved = true;
      }
    }
    if (!moved) break;
  }
  const voronoi = Delaunay.from(
    seeds,
    seed => seed[1][0],
    seed => seed[1][1]
  ).voronoi([minLon - 0.02, minLat - 0.02, maxLon + 0.02, maxLat + 0.02]);
  const clip = polygonClipping.intersection as unknown as (a: unknown, b: unknown) => number[][][][] | null;

  const townships: TownshipShape[] = [];
  seeds.forEach(([name, coord], index) => {
    const cell = voronoi.cellPolygon(index) as Ring | null;
    if (!cell || cell.length < 4) return;
    const ring: Ring = [];
    for (let i = 0; i < cell.length - 1; i++) ring.push(cell[i], ...jaggedSegment(cell[i], cell[i + 1]));
    ring.push(ring[0]);
    const clipped = clip(county, [ring]);
    if (!clipped?.length) return;
    const [x, y] = project(coord);
    townships.push({ name, d: toPath(clipped), x, y });
  });

  geometryCache = { width: MAP_WIDTH, height, outline: toPath(county), townships, hospital: project(HOSPITAL_COORD) };
  return geometryCache;
};

export const maskPhone = (value: string) => {
  const phone = String(value || "").trim();
  return phone.length >= 7 ? `${phone.slice(0, 3)}****${phone.slice(-4)}` : phone;
};
