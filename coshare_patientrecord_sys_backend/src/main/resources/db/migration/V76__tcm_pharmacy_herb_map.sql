-- 中药房药架位置映射：5 个药架 × 5 层 × 12 格 = 300 个演示格位
-- 演示药材名与库存仅用于前端验收，后续通过导入/对接覆盖（herb_code + data_source）

CREATE TABLE IF NOT EXISTS tcm_pharmacy_herb_shelves (
  shelf_no INT PRIMARY KEY,
  name VARCHAR(32) NOT NULL,
  orientation VARCHAR(8) NOT NULL,
  layer_count INT NOT NULL DEFAULT 5,
  column_count INT NOT NULL DEFAULT 12,
  sort_order INT NOT NULL,
  layout_x INT NOT NULL,
  layout_y INT NOT NULL,
  layout_w INT NOT NULL,
  layout_h INT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tcm_pharmacy_herb_slots (
  shelf_no INT NOT NULL,
  layer_no INT NOT NULL,
  column_no INT NOT NULL,
  herb_code VARCHAR(64) NULL,
  herb_name VARCHAR(80) NULL,
  alias VARCHAR(160) NULL,
  pinyin VARCHAR(160) NULL,
  stock_quantity DECIMAL(12,2) NOT NULL DEFAULT 0,
  warn_threshold DECIMAL(12,2) NOT NULL DEFAULT 0,
  data_source VARCHAR(16) NOT NULL DEFAULT 'DEMO_SEED',
  updated_by VARCHAR(100) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (shelf_no, layer_no, column_no),
  UNIQUE KEY uk_slot_herb_code (herb_code),
  UNIQUE KEY uk_slot_herb_name (herb_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tcm_pharmacy_herb_map_state (
  id VARCHAR(32) PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 1,
  updated_by VARCHAR(100) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO tcm_pharmacy_herb_shelves
  (shelf_no, name, orientation, layer_count, column_count, sort_order, layout_x, layout_y, layout_w, layout_h)
VALUES
  (1, '1号架', 'H', 5, 12, 1, 40, 40, 220, 80),
  (2, '2号架', 'H', 5, 12, 2, 330, 40, 240, 80),
  (3, '3号架', 'H', 5, 12, 3, 640, 40, 220, 80),
  (4, '4号架', 'V', 5, 12, 4, 270, 140, 60, 300),
  (5, '5号架', 'V', 5, 12, 5, 580, 140, 60, 300);

INSERT INTO tcm_pharmacy_herb_map_state (id, revision, updated_by)
VALUES ('default', 1, 'system')
ON DUPLICATE KEY UPDATE revision = revision;

INSERT INTO tcm_pharmacy_herb_slots
  (shelf_no, layer_no, column_no, herb_code, herb_name, alias, pinyin,
   stock_quantity, warn_threshold, data_source, updated_by)
SELECT
  FLOOR((h.seq - 1) / 60) + 1,
  FLOOR(((h.seq - 1) % 60) / 12) + 1,
  ((h.seq - 1) % 12) + 1,
  CONCAT('H', LPAD(h.seq, 4, '0')),
  h.herb_name,
  NULL,
  NULL,
  CASE
    WHEN MOD(h.seq, 17) = 0 THEN 0
    WHEN MOD(h.seq, 11) = 0 THEN 20 + MOD(h.seq, 15)
    ELSE 150 + MOD(h.seq * 53, 700)
  END,
  60 + MOD(h.seq * 17, 60),
  'DEMO_SEED',
  'system'
FROM (
    SELECT 1 AS seq, '人参' AS herb_name
    UNION ALL SELECT 2 AS seq, '党参' AS herb_name
    UNION ALL SELECT 3 AS seq, '太子参' AS herb_name
    UNION ALL SELECT 4 AS seq, '黄芪' AS herb_name
    UNION ALL SELECT 5 AS seq, '白术' AS herb_name
    UNION ALL SELECT 6 AS seq, '茯苓' AS herb_name
    UNION ALL SELECT 7 AS seq, '甘草' AS herb_name
    UNION ALL SELECT 8 AS seq, '当归' AS herb_name
    UNION ALL SELECT 9 AS seq, '熟地黄' AS herb_name
    UNION ALL SELECT 10 AS seq, '生地黄' AS herb_name
    UNION ALL SELECT 11 AS seq, '白芍' AS herb_name
    UNION ALL SELECT 12 AS seq, '赤芍' AS herb_name
    UNION ALL SELECT 13 AS seq, '川芎' AS herb_name
    UNION ALL SELECT 14 AS seq, '丹参' AS herb_name
    UNION ALL SELECT 15 AS seq, '桃仁' AS herb_name
    UNION ALL SELECT 16 AS seq, '红花' AS herb_name
    UNION ALL SELECT 17 AS seq, '鸡血藤' AS herb_name
    UNION ALL SELECT 18 AS seq, '枸杞子' AS herb_name
    UNION ALL SELECT 19 AS seq, '麦冬' AS herb_name
    UNION ALL SELECT 20 AS seq, '天冬' AS herb_name
    UNION ALL SELECT 21 AS seq, '石斛' AS herb_name
    UNION ALL SELECT 22 AS seq, '玉竹' AS herb_name
    UNION ALL SELECT 23 AS seq, '黄精' AS herb_name
    UNION ALL SELECT 24 AS seq, '山药' AS herb_name
    UNION ALL SELECT 25 AS seq, '陈皮' AS herb_name
    UNION ALL SELECT 26 AS seq, '青皮' AS herb_name
    UNION ALL SELECT 27 AS seq, '枳实' AS herb_name
    UNION ALL SELECT 28 AS seq, '枳壳' AS herb_name
    UNION ALL SELECT 29 AS seq, '木香' AS herb_name
    UNION ALL SELECT 30 AS seq, '香附' AS herb_name
    UNION ALL SELECT 31 AS seq, '厚朴' AS herb_name
    UNION ALL SELECT 32 AS seq, '砂仁' AS herb_name
    UNION ALL SELECT 33 AS seq, '半夏' AS herb_name
    UNION ALL SELECT 34 AS seq, '竹茹' AS herb_name
    UNION ALL SELECT 35 AS seq, '桔梗' AS herb_name
    UNION ALL SELECT 36 AS seq, '杏仁' AS herb_name
    UNION ALL SELECT 37 AS seq, '麻黄' AS herb_name
    UNION ALL SELECT 38 AS seq, '桂枝' AS herb_name
    UNION ALL SELECT 39 AS seq, '紫苏叶' AS herb_name
    UNION ALL SELECT 40 AS seq, '荆芥' AS herb_name
    UNION ALL SELECT 41 AS seq, '防风' AS herb_name
    UNION ALL SELECT 42 AS seq, '羌活' AS herb_name
    UNION ALL SELECT 43 AS seq, '独活' AS herb_name
    UNION ALL SELECT 44 AS seq, '白芷' AS herb_name
    UNION ALL SELECT 45 AS seq, '细辛' AS herb_name
    UNION ALL SELECT 46 AS seq, '薄荷' AS herb_name
    UNION ALL SELECT 47 AS seq, '菊花' AS herb_name
    UNION ALL SELECT 48 AS seq, '柴胡' AS herb_name
    UNION ALL SELECT 49 AS seq, '葛根' AS herb_name
    UNION ALL SELECT 50 AS seq, '升麻' AS herb_name
    UNION ALL SELECT 51 AS seq, '黄芩' AS herb_name
    UNION ALL SELECT 52 AS seq, '黄连' AS herb_name
    UNION ALL SELECT 53 AS seq, '黄柏' AS herb_name
    UNION ALL SELECT 54 AS seq, '栀子' AS herb_name
    UNION ALL SELECT 55 AS seq, '金银花' AS herb_name
    UNION ALL SELECT 56 AS seq, '连翘' AS herb_name
    UNION ALL SELECT 57 AS seq, '板蓝根' AS herb_name
    UNION ALL SELECT 58 AS seq, '蒲公英' AS herb_name
    UNION ALL SELECT 59 AS seq, '鱼腥草' AS herb_name
    UNION ALL SELECT 60 AS seq, '大黄' AS herb_name
    UNION ALL SELECT 61 AS seq, '芒硝' AS herb_name
    UNION ALL SELECT 62 AS seq, '火麻仁' AS herb_name
    UNION ALL SELECT 63 AS seq, '郁李仁' AS herb_name
    UNION ALL SELECT 64 AS seq, '泽泻' AS herb_name
    UNION ALL SELECT 65 AS seq, '车前子' AS herb_name
    UNION ALL SELECT 66 AS seq, '薏苡仁' AS herb_name
    UNION ALL SELECT 67 AS seq, '猪苓' AS herb_name
    UNION ALL SELECT 68 AS seq, '茵陈' AS herb_name
    UNION ALL SELECT 69 AS seq, '附子' AS herb_name
    UNION ALL SELECT 70 AS seq, '干姜' AS herb_name
    UNION ALL SELECT 71 AS seq, '肉桂' AS herb_name
    UNION ALL SELECT 72 AS seq, '吴茱萸' AS herb_name
    UNION ALL SELECT 73 AS seq, '艾叶' AS herb_name
    UNION ALL SELECT 74 AS seq, '酸枣仁' AS herb_name
    UNION ALL SELECT 75 AS seq, '柏子仁' AS herb_name
    UNION ALL SELECT 76 AS seq, '远志' AS herb_name
    UNION ALL SELECT 77 AS seq, '龙骨' AS herb_name
    UNION ALL SELECT 78 AS seq, '牡蛎' AS herb_name
    UNION ALL SELECT 79 AS seq, '钩藤' AS herb_name
    UNION ALL SELECT 80 AS seq, '天麻' AS herb_name
    UNION ALL SELECT 81 AS seq, '地龙' AS herb_name
    UNION ALL SELECT 82 AS seq, '三七' AS herb_name
    UNION ALL SELECT 83 AS seq, '延胡索' AS herb_name
    UNION ALL SELECT 84 AS seq, '牛膝' AS herb_name
    UNION ALL SELECT 85 AS seq, '杜仲' AS herb_name
    UNION ALL SELECT 86 AS seq, '续断' AS herb_name
    UNION ALL SELECT 87 AS seq, '桑寄生' AS herb_name
    UNION ALL SELECT 88 AS seq, '淫羊藿' AS herb_name
    UNION ALL SELECT 89 AS seq, '补骨脂' AS herb_name
    UNION ALL SELECT 90 AS seq, '山茱萸' AS herb_name
    UNION ALL SELECT 91 AS seq, '五味子' AS herb_name
    UNION ALL SELECT 92 AS seq, '麻黄根' AS herb_name
    UNION ALL SELECT 93 AS seq, '紫苏子' AS herb_name
    UNION ALL SELECT 94 AS seq, '紫苏梗' AS herb_name
    UNION ALL SELECT 95 AS seq, '紫菀' AS herb_name
    UNION ALL SELECT 96 AS seq, '款冬花' AS herb_name
    UNION ALL SELECT 97 AS seq, '百部' AS herb_name
    UNION ALL SELECT 98 AS seq, '枇杷叶' AS herb_name
    UNION ALL SELECT 99 AS seq, '桑白皮' AS herb_name
    UNION ALL SELECT 100 AS seq, '葶苈子' AS herb_name
    UNION ALL SELECT 101 AS seq, '白果' AS herb_name
    UNION ALL SELECT 102 AS seq, '前胡' AS herb_name
    UNION ALL SELECT 103 AS seq, '白前' AS herb_name
    UNION ALL SELECT 104 AS seq, '旋覆花' AS herb_name
    UNION ALL SELECT 105 AS seq, '川贝母' AS herb_name
    UNION ALL SELECT 106 AS seq, '浙贝母' AS herb_name
    UNION ALL SELECT 107 AS seq, '瓜蒌' AS herb_name
    UNION ALL SELECT 108 AS seq, '天南星' AS herb_name
    UNION ALL SELECT 109 AS seq, '白附子' AS herb_name
    UNION ALL SELECT 110 AS seq, '芥子' AS herb_name
    UNION ALL SELECT 111 AS seq, '皂荚' AS herb_name
    UNION ALL SELECT 112 AS seq, '海藻' AS herb_name
    UNION ALL SELECT 113 AS seq, '昆布' AS herb_name
    UNION ALL SELECT 114 AS seq, '海蛤壳' AS herb_name
    UNION ALL SELECT 115 AS seq, '瓦楞子' AS herb_name
    UNION ALL SELECT 116 AS seq, '礞石' AS herb_name
    UNION ALL SELECT 117 AS seq, '苦杏仁' AS herb_name
    UNION ALL SELECT 118 AS seq, '生姜' AS herb_name
    UNION ALL SELECT 119 AS seq, '香薷' AS herb_name
    UNION ALL SELECT 120 AS seq, '苍耳子' AS herb_name
    UNION ALL SELECT 121 AS seq, '辛夷' AS herb_name
    UNION ALL SELECT 122 AS seq, '牛蒡子' AS herb_name
    UNION ALL SELECT 123 AS seq, '蝉蜕' AS herb_name
    UNION ALL SELECT 124 AS seq, '桑叶' AS herb_name
    UNION ALL SELECT 125 AS seq, '蔓荆子' AS herb_name
    UNION ALL SELECT 126 AS seq, '淡豆豉' AS herb_name
    UNION ALL SELECT 127 AS seq, '浮萍' AS herb_name
    UNION ALL SELECT 128 AS seq, '木贼' AS herb_name
    UNION ALL SELECT 129 AS seq, '石膏' AS herb_name
    UNION ALL SELECT 130 AS seq, '寒水石' AS herb_name
    UNION ALL SELECT 131 AS seq, '知母' AS herb_name
    UNION ALL SELECT 132 AS seq, '芦根' AS herb_name
    UNION ALL SELECT 133 AS seq, '天花粉' AS herb_name
    UNION ALL SELECT 134 AS seq, '竹叶' AS herb_name
    UNION ALL SELECT 135 AS seq, '淡竹叶' AS herb_name
    UNION ALL SELECT 136 AS seq, '夏枯草' AS herb_name
    UNION ALL SELECT 137 AS seq, '决明子' AS herb_name
    UNION ALL SELECT 138 AS seq, '谷精草' AS herb_name
    UNION ALL SELECT 139 AS seq, '密蒙花' AS herb_name
    UNION ALL SELECT 140 AS seq, '青葙子' AS herb_name
    UNION ALL SELECT 141 AS seq, '龙胆' AS herb_name
    UNION ALL SELECT 142 AS seq, '秦皮' AS herb_name
    UNION ALL SELECT 143 AS seq, '苦参' AS herb_name
    UNION ALL SELECT 144 AS seq, '白鲜皮' AS herb_name
    UNION ALL SELECT 145 AS seq, '穿心莲' AS herb_name
    UNION ALL SELECT 146 AS seq, '大青叶' AS herb_name
    UNION ALL SELECT 147 AS seq, '青黛' AS herb_name
    UNION ALL SELECT 148 AS seq, '贯众' AS herb_name
    UNION ALL SELECT 149 AS seq, '紫花地丁' AS herb_name
    UNION ALL SELECT 150 AS seq, '野菊花' AS herb_name
    UNION ALL SELECT 151 AS seq, '重楼' AS herb_name
    UNION ALL SELECT 152 AS seq, '拳参' AS herb_name
    UNION ALL SELECT 153 AS seq, '漏芦' AS herb_name
    UNION ALL SELECT 154 AS seq, '土茯苓' AS herb_name
    UNION ALL SELECT 155 AS seq, '金荞麦' AS herb_name
    UNION ALL SELECT 156 AS seq, '红藤' AS herb_name
    UNION ALL SELECT 157 AS seq, '败酱草' AS herb_name
    UNION ALL SELECT 158 AS seq, '射干' AS herb_name
    UNION ALL SELECT 159 AS seq, '山豆根' AS herb_name
    UNION ALL SELECT 160 AS seq, '马勃' AS herb_name
    UNION ALL SELECT 161 AS seq, '白头翁' AS herb_name
    UNION ALL SELECT 162 AS seq, '马齿苋' AS herb_name
    UNION ALL SELECT 163 AS seq, '鸦胆子' AS herb_name
    UNION ALL SELECT 164 AS seq, '半边莲' AS herb_name
    UNION ALL SELECT 165 AS seq, '白花蛇舌草' AS herb_name
    UNION ALL SELECT 166 AS seq, '山慈菇' AS herb_name
    UNION ALL SELECT 167 AS seq, '玄参' AS herb_name
    UNION ALL SELECT 168 AS seq, '牡丹皮' AS herb_name
    UNION ALL SELECT 169 AS seq, '紫草' AS herb_name
    UNION ALL SELECT 170 AS seq, '水牛角' AS herb_name
    UNION ALL SELECT 171 AS seq, '青蒿' AS herb_name
    UNION ALL SELECT 172 AS seq, '白薇' AS herb_name
    UNION ALL SELECT 173 AS seq, '地骨皮' AS herb_name
    UNION ALL SELECT 174 AS seq, '银柴胡' AS herb_name
    UNION ALL SELECT 175 AS seq, '胡黄连' AS herb_name
    UNION ALL SELECT 176 AS seq, '番泻叶' AS herb_name
    UNION ALL SELECT 177 AS seq, '芦荟' AS herb_name
    UNION ALL SELECT 178 AS seq, '甘遂' AS herb_name
    UNION ALL SELECT 179 AS seq, '大戟' AS herb_name
    UNION ALL SELECT 180 AS seq, '芫花' AS herb_name
    UNION ALL SELECT 181 AS seq, '牵牛子' AS herb_name
    UNION ALL SELECT 182 AS seq, '商陆' AS herb_name
    UNION ALL SELECT 183 AS seq, '巴豆' AS herb_name
    UNION ALL SELECT 184 AS seq, '威灵仙' AS herb_name
    UNION ALL SELECT 185 AS seq, '川乌' AS herb_name
    UNION ALL SELECT 186 AS seq, '草乌' AS herb_name
    UNION ALL SELECT 187 AS seq, '蕲蛇' AS herb_name
    UNION ALL SELECT 188 AS seq, '乌梢蛇' AS herb_name
    UNION ALL SELECT 189 AS seq, '木瓜' AS herb_name
    UNION ALL SELECT 190 AS seq, '蚕沙' AS herb_name
    UNION ALL SELECT 191 AS seq, '伸筋草' AS herb_name
    UNION ALL SELECT 192 AS seq, '寻骨风' AS herb_name
    UNION ALL SELECT 193 AS seq, '松节' AS herb_name
    UNION ALL SELECT 194 AS seq, '海风藤' AS herb_name
    UNION ALL SELECT 195 AS seq, '青风藤' AS herb_name
    UNION ALL SELECT 196 AS seq, '丁公藤' AS herb_name
    UNION ALL SELECT 197 AS seq, '桑枝' AS herb_name
    UNION ALL SELECT 198 AS seq, '豨莶草' AS herb_name
    UNION ALL SELECT 199 AS seq, '臭梧桐' AS herb_name
    UNION ALL SELECT 200 AS seq, '海桐皮' AS herb_name
    UNION ALL SELECT 201 AS seq, '络石藤' AS herb_name
    UNION ALL SELECT 202 AS seq, '雷公藤' AS herb_name
    UNION ALL SELECT 203 AS seq, '老鹳草' AS herb_name
    UNION ALL SELECT 204 AS seq, '五加皮' AS herb_name
    UNION ALL SELECT 205 AS seq, '狗脊' AS herb_name
    UNION ALL SELECT 206 AS seq, '千年健' AS herb_name
    UNION ALL SELECT 207 AS seq, '鹿衔草' AS herb_name
    UNION ALL SELECT 208 AS seq, '路路通' AS herb_name
    UNION ALL SELECT 209 AS seq, '藿香' AS herb_name
    UNION ALL SELECT 210 AS seq, '佩兰' AS herb_name
    UNION ALL SELECT 211 AS seq, '苍术' AS herb_name
    UNION ALL SELECT 212 AS seq, '白豆蔻' AS herb_name
    UNION ALL SELECT 213 AS seq, '草豆蔻' AS herb_name
    UNION ALL SELECT 214 AS seq, '草果' AS herb_name
    UNION ALL SELECT 215 AS seq, '冬瓜皮' AS herb_name
    UNION ALL SELECT 216 AS seq, '玉米须' AS herb_name
    UNION ALL SELECT 217 AS seq, '葫芦' AS herb_name
    UNION ALL SELECT 218 AS seq, '香加皮' AS herb_name
    UNION ALL SELECT 219 AS seq, '枳椇子' AS herb_name
    UNION ALL SELECT 220 AS seq, '泽漆' AS herb_name
    UNION ALL SELECT 221 AS seq, '蝼蛄' AS herb_name
    UNION ALL SELECT 222 AS seq, '滑石' AS herb_name
    UNION ALL SELECT 223 AS seq, '木通' AS herb_name
    UNION ALL SELECT 224 AS seq, '通草' AS herb_name
    UNION ALL SELECT 225 AS seq, '瞿麦' AS herb_name
    UNION ALL SELECT 226 AS seq, '萹蓄' AS herb_name
    UNION ALL SELECT 227 AS seq, '地肤子' AS herb_name
    UNION ALL SELECT 228 AS seq, '海金沙' AS herb_name
    UNION ALL SELECT 229 AS seq, '石韦' AS herb_name
    UNION ALL SELECT 230 AS seq, '冬葵子' AS herb_name
    UNION ALL SELECT 231 AS seq, '灯心草' AS herb_name
    UNION ALL SELECT 232 AS seq, '萆薢' AS herb_name
    UNION ALL SELECT 233 AS seq, '金钱草' AS herb_name
    UNION ALL SELECT 234 AS seq, '虎杖' AS herb_name
    UNION ALL SELECT 235 AS seq, '地耳草' AS herb_name
    UNION ALL SELECT 236 AS seq, '垂盆草' AS herb_name
    UNION ALL SELECT 237 AS seq, '小茴香' AS herb_name
    UNION ALL SELECT 238 AS seq, '丁香' AS herb_name
    UNION ALL SELECT 239 AS seq, '高良姜' AS herb_name
    UNION ALL SELECT 240 AS seq, '花椒' AS herb_name
    UNION ALL SELECT 241 AS seq, '荜茇' AS herb_name
    UNION ALL SELECT 242 AS seq, '荜澄茄' AS herb_name
    UNION ALL SELECT 243 AS seq, '胡椒' AS herb_name
    UNION ALL SELECT 244 AS seq, '沉香' AS herb_name
    UNION ALL SELECT 245 AS seq, '檀香' AS herb_name
    UNION ALL SELECT 246 AS seq, '川楝子' AS herb_name
    UNION ALL SELECT 247 AS seq, '乌药' AS herb_name
    UNION ALL SELECT 248 AS seq, '青木香' AS herb_name
    UNION ALL SELECT 249 AS seq, '荔枝核' AS herb_name
    UNION ALL SELECT 250 AS seq, '佛手' AS herb_name
    UNION ALL SELECT 251 AS seq, '香橼' AS herb_name
    UNION ALL SELECT 252 AS seq, '玫瑰花' AS herb_name
    UNION ALL SELECT 253 AS seq, '绿萼梅' AS herb_name
    UNION ALL SELECT 254 AS seq, '娑罗子' AS herb_name
    UNION ALL SELECT 255 AS seq, '薤白' AS herb_name
    UNION ALL SELECT 256 AS seq, '大腹皮' AS herb_name
    UNION ALL SELECT 257 AS seq, '甘松' AS herb_name
    UNION ALL SELECT 258 AS seq, '九香虫' AS herb_name
    UNION ALL SELECT 259 AS seq, '刀豆' AS herb_name
    UNION ALL SELECT 260 AS seq, '柿蒂' AS herb_name
    UNION ALL SELECT 261 AS seq, '山楂' AS herb_name
    UNION ALL SELECT 262 AS seq, '神曲' AS herb_name
    UNION ALL SELECT 263 AS seq, '麦芽' AS herb_name
    UNION ALL SELECT 264 AS seq, '稻芽' AS herb_name
    UNION ALL SELECT 265 AS seq, '莱菔子' AS herb_name
    UNION ALL SELECT 266 AS seq, '鸡内金' AS herb_name
    UNION ALL SELECT 267 AS seq, '使君子' AS herb_name
    UNION ALL SELECT 268 AS seq, '苦楝皮' AS herb_name
    UNION ALL SELECT 269 AS seq, '槟榔' AS herb_name
    UNION ALL SELECT 270 AS seq, '南瓜子' AS herb_name
    UNION ALL SELECT 271 AS seq, '鹤草芽' AS herb_name
    UNION ALL SELECT 272 AS seq, '雷丸' AS herb_name
    UNION ALL SELECT 273 AS seq, '鹤虱' AS herb_name
    UNION ALL SELECT 274 AS seq, '榧子' AS herb_name
    UNION ALL SELECT 275 AS seq, '小蓟' AS herb_name
    UNION ALL SELECT 276 AS seq, '大蓟' AS herb_name
    UNION ALL SELECT 277 AS seq, '地榆' AS herb_name
    UNION ALL SELECT 278 AS seq, '槐花' AS herb_name
    UNION ALL SELECT 279 AS seq, '侧柏叶' AS herb_name
    UNION ALL SELECT 280 AS seq, '白茅根' AS herb_name
    UNION ALL SELECT 281 AS seq, '苎麻根' AS herb_name
    UNION ALL SELECT 282 AS seq, '羊蹄' AS herb_name
    UNION ALL SELECT 283 AS seq, '茜草' AS herb_name
    UNION ALL SELECT 284 AS seq, '蒲黄' AS herb_name
    UNION ALL SELECT 285 AS seq, '花蕊石' AS herb_name
    UNION ALL SELECT 286 AS seq, '降香' AS herb_name
    UNION ALL SELECT 287 AS seq, '白及' AS herb_name
    UNION ALL SELECT 288 AS seq, '仙鹤草' AS herb_name
    UNION ALL SELECT 289 AS seq, '紫珠' AS herb_name
    UNION ALL SELECT 290 AS seq, '棕榈炭' AS herb_name
    UNION ALL SELECT 291 AS seq, '血余炭' AS herb_name
    UNION ALL SELECT 292 AS seq, '藕节' AS herb_name
    UNION ALL SELECT 293 AS seq, '郁金' AS herb_name
    UNION ALL SELECT 294 AS seq, '姜黄' AS herb_name
    UNION ALL SELECT 295 AS seq, '乳香' AS herb_name
    UNION ALL SELECT 296 AS seq, '没药' AS herb_name
    UNION ALL SELECT 297 AS seq, '五灵脂' AS herb_name
    UNION ALL SELECT 298 AS seq, '益母草' AS herb_name
    UNION ALL SELECT 299 AS seq, '泽兰' AS herb_name
    UNION ALL SELECT 300 AS seq, '王不留行' AS herb_name
) h
ORDER BY h.seq;
