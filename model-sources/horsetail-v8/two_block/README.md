# 营养茎 · 双格拆分（lower / upper）

营养茎 1.45 格、矮版 1.305 格，都超过一格。按方案 A 做成"玫瑰丛式"的两格方块，
模型在这里拆好，**没有改动你的工程**。

---

## 1. 文件

| 文件 | 元素 | 面 | 高度 | 用途 |
|---|---:|---:|---:|---|
| `vegetative_lower.json` | 41 | 85 | 0..16 | 下半格，承载整株数据 |
| `vegetative_upper.json` | 41 | 85 | 0..7.2 | 上半格，纯展示壳 |
| `vegetative_short_lower.json` | 49 | 101 | 0..16 | 同上 |
| `vegetative_short_upper.json` | 33 | 69 | 0..4.88 | 同上 |

贴图仍是 `life_sanctuary:block/horsetail_atlas`，没有新增图集。

---

## 2. 怎么切的

- 完全在 y ≤ 16 的元素原样留在 lower
- 完全在 y ≥ 16 的元素整体下移 16 进 upper
- **跨界元素切一刀**：竖直面的 `v` 按高度线性重算
  （`v=0` 是元素顶端，所以 `v(y) = v1 + (b - y)/(b - a) × (v2 - v1)`）
- 切开那一侧的横面（上块的下表面、下块的上表面）**直接删掉** ——
  这两个面会在 y=16 处同平面互相闪

**自检**：把 upper 整体上移 16 再与 lower 合并渲染，与原模型对比，
差异 12 个像素 / 129 600（全是切开处的边缘像素），几何与贴图是正确的。

---

## 3. 方块状态建议

工程里已经有现成的写法可以对齐（`DeadLycopodBlock` 的 `Part` 枚举，
序列化为 `single / lower / upper`）：

```java
public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
```

对应方块状态：

```json
{
  "variants": {
    "part=lower": { "model": "life_sanctuary:block/vegetative_lower" },
    "part=upper": { "model": "life_sanctuary:block/vegetative_upper" }
  }
}
```

营养茎永远占两格，所以不需要 `single`；如果以后有矮到一格内的形态再加。

---

## 4. 选中框建议

模型实测包围盒：

| | x | y | z |
|---|---|---|---|
| vegetative lower | 3.00 – 13.00 | 0 – 16 | 3.00 – 13.00 |
| vegetative upper | 4.80 – 11.20 | 0 – 7.20 | 4.80 – 11.20 |
| short lower | 3.00 – 13.00 | 0 – 16 | 3.00 – 13.00 |
| short upper | 5.25 – 10.75 | 0 – 4.88 | 5.25 – 10.75 |

叶片是零厚度薄面，纯按包围盒做选中框会显得"离得挺远也能点到"。
原版高大的花（`DoublePlantBlock`）直接用的是整格框，所以两个取法都说得过去：

- **稳妥**：`Block.box(3, 0, 3, 13, 16, 13)` / 上半按上表
- **保守**：`Block.box(4, 0, 4, 12, 16, 12)`，叶尖点不到但不虚

我建议上半用实际包围盒（本来就小），下半用 3..13。

---

## 5. 代码侧要注意的三件事

1. **数据只在 lower**。阶段、来源、种群、采集额度、计时都挂在下半格；
   upper 是纯展示，被单独破坏时要先找到 lower 再决定行为
   （`DeadLycopodBlock` / `RhizomeDecorationCleanup` 里已有 `HALF` 配对的写法可抄）。

2. **破坏联动**。挖任意一半都要同时清掉另一半，掉落只算一次。
   如果上半被玩家先挖到，`getDrops` 应该回落给 lower 处理。

3. **放置顺序**。先校验上方是否有空位，再放 lower，然后放 upper；
   上方被占就整体失败，不要只放一半。

---

## 6. 还没做的

- 没有生成 `.bbmodel` 拆分包：这两份是**游戏导出**，编辑源仍是
  `models/vegetative.bbmodel`（改完重跑 `tools/split_two_block.py` 即可）
- 没有动你的任何代码（`E:\life_sanctuary_Mod_Code\life_sanctuary_Mod` 只读）
