# 问荆 · 最终版（V8）

六个模型、两张图集。

| 模型 | 元素 | 面 | 高度 | 方块 |
|---|---:|---:|---:|---|
| `vegetative` | 73 | 150 | 1.450 格 | `equisetum_arvense` |
| `vegetative_short` | 73 | 150 | **1.305 格（−10%）** | 同上，形态差分 |
| `juvenile` | 49 | 102 | 0.720 格 | `..._sprouts` |
| `gametophyte` | 8 | 16 | 0.173 格 | `..._gametophyte` |
| `fertile_stem` | 32 | 192 | 0.990 格 | `..._spore_stem` |
| `fertile_stem_rust` | 32 | 192 | 0.990 格 | 同上，材质差分 |

贴图两张：`horsetail_atlas.png`（64×64，六个模型共用）与
`horsetail_atlas_rust.png`（同布局，孢子茎换红褐）。

---

## 1. vegetative 的形态差分

**做法：整株沿 y 轴压缩 0.9，x/z 完全不动。**

- 主轴 21.4 → 19.26
- 每一环轮生枝的 y、上升量 rise、旋转原点的 y 全部 ×0.9
- 顶叶同理

因为是对**全部元素一起**压的，轮生枝仍然从主轴上原来的相对位置伸出，
不会出现"枝子悬空"或"扎进别处"；横向展开宽度保持不变。
所以整株是**变矮变敦实**，不是整体缩小一圈。

最高点 23.20 → 20.88 px，正好矮 10.0%。

---

## 2. 共面闪烁的处理

`vegetative` / `juvenile` 里同一方位的叶面全落在同一个平面上（x=8 或 z=8），
相邻环在 y 上又有重叠 —— 一开始 vegetative 报了 **72 处共面**，
进游戏会在叶与叶交界处闪。

修法：**按环序号做 4 相位错开**，每片叶子沿自身法线偏移
`(-0.15, -0.05, +0.05, +0.15)` 中的一个（按 `环序号 % 4` 取）。

- 相邻环差 0.10，隔一环 0.20，隔两环 0.30 → 所有会重叠的组合都拉开
- 最大位移 0.15 像素，远小于主轴半径 0.25 → 叶根仍埋在主轴上，外观看不出
- 同一环里对向的两片虽然同面，但重叠区就在主轴附近、被主轴挡住

检查结果：

```
vegetative        共面重叠 0 处 ✓
vegetative_short  共面重叠 0 处 ✓
gametophyte       共面重叠 0 处 ✓
fertile_stem      共面重叠 0 处 ✓
fertile_stem_rust 共面重叠 0 处 ✓
juvenile          共面重叠 4 处（顶叶 0 号与 4 号，重叠区在主轴内部，看不见）
```

---

## 3. 图集

64×64，**12 个区块**，每块四周留 2 像素并把边缘像素外扩（防 mipmap 串色）。

| 变化 | 效果 |
|---|---|
| 孢子茎主干条 8×128 → 8×16 | 整条几乎纯色（每行亮度 213±2），128 行纯属浪费 |
| juvenile 主干并入 vegetative 条 | 两者本来就是同一段（起点差 0.14 像素），区块 13 → 12 |
| 全部像素按实际引用打包 | 45 056 px² → 4 096 px²（**−91%**） |

布局见 `textures/layout.json` 与 `preview/atlas_layout.png`。

---

## 4. 游戏侧

`java_export/` 六个文件，UV 已归一化 0..16，贴图路径：

- `life_sanctuary:block/horsetail_atlas` —— 除差分外的五个
- `life_sanctuary:block/horsetail_atlas_rust` —— 孢子茎红褐差分

两套差分怎么用：
- 同方块随机：方块状态里给两个 model 配 weight
- 分群系：成煤森林用 straw、煤沼用 rust；营养茎密林用高的、湿地用矮的
- 也适合做"生长阶段"或"群系形态差异"

---

## 5. 目录

```
最终版 V8/
  models/      六个 .bbmodel（贴图已内嵌）
  textures/    horsetail_atlas.png / horsetail_atlas_rust.png / layout.json
  java_export/ 六个游戏用方块模型
  preview/     overview / vegetative_variant / atlas_layout(_rust)
  manifest.json
  check_report.txt
```

---

## 6. 后续

后面 73 个物种按 `植物精确设计\植物建模规范_v1.md` 执行 ——
那里面把这一轮所有踩过的坑、可复用的参数、提交前检查清单都写进去了。
