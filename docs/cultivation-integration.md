# 培养柜物种接入说明

以当前代码与用户后续规则为准。培养柜公共基础已接入，但尚未注册真实物种的培育方案；本文不替任何物种补造配子体、幼苗或成年阶段。

## 新物种作者需要提供什么

| 内容 | 接入位置 | 要求 |
| --- | --- | --- |
| 物种 ID 与阶段元数据 | PlantSpeciesRegistry | 先登记物种；沿用稳定命名空间 |
| 培育阶段链 | CultivationRecipe | 明确每段阶段键、逻辑产物 ID、自然耗时最小／最大 tick |
| 每段真实物品 | CultivationProducts | 每个物种＋逻辑产物 ID 对应一份 ItemStack 模板，数量必须为 1 |
| 方案注册 | CultivationRecipes | 每个物种一个方案，方案 ID 唯一，配置期注册一次 |
| 物种显示名 | 语言资源 | species.namespace.species_path，例如 species.life_sanctuary.equisetum_arvense |
| 植物物品使用效果 | 物种自己的物品／放置逻辑 | 培养柜只交付物品，不替物品编写种植、生长或繁殖效果 |

培育阶段不必与世界植株阶段同名，也不必覆盖世界中的全部阶段。最后一个培育阶段只表示培养柜最终产物，不能据此把世界幼苗自动标记为完全成年。需要直接培育的物种可以只有一段；特殊时长必须在该物种明确填写。

自然耗时由机器按 60% 换算且向上取整，物种作者不要预先乘一次 60%，否则会重复打折。时间范围端点为正 int，单位为 tick。当前机器每袋总产量固定四份，各阶段仍是四份，不因进入下一段变成十六份。

## 注册顺序

1. 注册真实物品并登记物种元数据。
2. 确认该物种的阶段、时长及每段产物设计已经确定，构造 CultivationRecipe。
3. 在物品注册完成后的配置期，为方案涉及的全部逻辑产物调用 CultivationProducts.register。
4. 调用 CultivationRecipes.register 注册方案。可在 common setup 的 enqueueWork 中完成物种配置，避免在物品还未注册时构造物品模板。
5. 游戏内使用 `/life_sanctuary cultivation recipes` 检查缺失映射，再按 cultivation-verification.md 验证。

以下仅是参数化接入示意，不是已注册方案，也不提供虚构物种参数：

```java
// species、recipeId、phases、templates 均由该物种作者提供。
var recipe = new CultivationRecipe(recipeId, species, phases);
for (var phase : recipe.phases()) {
    if (!templates.containsKey(phase.output())) {
        throw new IllegalArgumentException("Missing cultivation output template");
    }
}
for (var entry : templates.entrySet()) {
    CultivationProducts.register(species, entry.getKey(), entry.getValue());
}
CultivationRecipes.register(recipe);
```

同一物种多个阶段使用相同逻辑产物 ID 时，只注册一次模板；需要不同物品或不同组件时使用不同逻辑产物 ID。模板保留组件并防御复制，不要求为每个物种另造一种袋子。注册器拒绝覆盖和重复，不支持游戏中热重载或自动补偿；配置错误应在配置期修正，不能在玩家投入后改写原批次。

## 已有行为不要重复实现

- 单柜两槽，两个同朝向左右单柜连接后四槽；每半柜独立保存自己的两槽，不能把临时四槽视图再作为一台机器计时或保存第二份批次。
- 手持满孢子袋且有空槽时可直投；其他情况右键开 UI；潜行右键旁路柜体，用于放置手持方块。玩家取物必须通过 UI。
- UI 点击／Shift 投入、完整手工领取、顶部输入、底部仅最终产物输出已经接入，物种代码不要另行扣袋或发四份物品。
- 缺失方案或任一产物映射不扣袋。只读／损坏数据拒绝执行，不清空恢复、不自动退款。
- 有电且区块活动时计时，双柜任一半供电即可；离线不补算。阶段时长只在开工／转段时选定一次，不在查询、模拟或轮询时重抽。
- 下方漏斗可逐件抽取最终产物；余量未取完仍占用原名额。物种代码不能再次按原批次生成完整四份。
- 中间产物领取终止整个批次；不会再保留另一份进行下一阶段。
- 预览标签 CultivationPreviewSpecies 只作 UI 显示，不是物种判定或真实物品数据来源。

## 保存边界

| 层级 | 当前格式 |
| --- | --- |
| 柜体信封 | CabinetVersion 1，含 Cultivation 和 Outputs |
| 本地培育槽 | CultivationSlotsCodec V2，每半柜实际两槽，旧四槽读取能力保留 |
| 原培育批次 | CultivationBatchCodec V1，完整配方快照 |
| 本地两组输出 | CultivationOutputSlotsCodec V1 |
| 输出批次 | CultivationOutputBatchCodec V2，原完成任务＋冻结标记＋剩余实物 |

已经投入的任务按保存的旧方案执行，不套用新注册表默认值。已物化输出按保存的真实物品领取，不依赖当前映射重新生成。未知版本、无法解析的物品和损坏字段保留原 NBT；不要把 Preserved 当作空柜或已取完。

## 本模块尚待完成或验证

真实物种方案与产物映射、单柜合成材料、正式外观／模型、Jade 实际显示接入仍未配置或实现。侧面／无方向 ItemHandler 当前不提供能力，不应宣称所有方向都可进出。

用户已逐步确认本地 compileJava 成功；没有游戏内端到端产物流、保存重进、性能基准、机械动力或航空学移动联调结果。结构旋转／镜像入口已实现，不等于第三方移动兼容已证明。公共事务不保证外部库存、区块和文件之间的崩溃原子性。
