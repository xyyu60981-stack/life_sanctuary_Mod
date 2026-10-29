# clump/ —— 丛生模型（游戏用）

由 `tools/emit_horsetail_clump.py` 从三个单位模型合成，**不要手改**，
改单位模型后重跑脚本即可。

| 前缀 | 含义 |
|---|---|
| `horsetail_nv_c{1-3}_{a\|j}_v{1-3}_lower/upper` | 营养茎组合：**c** 根数、**a** 全部成年 / **j** 最新一根是幼株、**v** 高度变体 |
| `horsetail_sp_c{1-3}_{straw\|rust}` | 孢子茎，按根数决定插在哪个空位 |

共 39 个：18 下半 + 15 上半 + 6 孢子茎。

- `c1_j_*` 只有幼株（11.5 高），**没有 upper**，所以上半格只有 15 个
- 孢子茎 15.8 高，永远只在下半格
- 贴图：营养茎与 straw 孢子茎用 `horsetail_atlas`，rust 孢子茎额外引用 `horsetail_atlas_rust`

## 校验

把 `_upper` 整体上移 16 与 `_lower` 合并渲染，与原合成模型逐像素比：

| 组合 | 差异像素（共 129 600） |
|---|---:|
| c1_a_v1 | 0 |
| c2_a_v2 | 0 |
| c3_a_v3 | 6 |
| c2_j_v1 | 0 |
| c3_j_v2 | 1 |

差异全部落在 y=16 的切口边缘，是栅格化误差，不是几何错误。

## 方块状态

`blockstate_lower.json` / `blockstate_upper.json` 是草稿：

- 营养茎按 `(nutritional_stems, growing)` 选模型组，组内 3 个变体**等权**随机
- 孢子茎按 `(spore_stem, nutritional_stems)` 选位置，straw:rust = **3:1**
- `blockstate_upper.json` 里的条件都带 `part=upper`

多权重（weight）在游戏里按方块坐标稳定取值，所以**同一状态的两株长得不一样**。

## 代码侧还差

1. `growing` 属性还没加进 `EquisetumArvenseBlock`
2. 只有幼株时不该生成上半格（否则会多一个空的 `part=upper` 方块）

## 枯萎态

horsetail_nv_w_c{1-3}_{a|j}_lower/upper 与 horsetail_sp_w_c{1-3}_{straw|rust}
共 17 个，几何与正常版完全一致，只是把贴图换成
horsetail_atlas_withered.png（同布局、去色偏暖灰褐）。

枯萎态不追求品种差异，只出一套高度变体（v1），所以文件数更少。

lockstate_lower.json / lockstate_upper.json 已经按

utritional_stems × growing × withering 全部铺好（18 + 10 条），
连同孢子茎的 3:1 权重，可以直接替换现有文件。