# 装饰毯与枯死木材配方接入

对应2026-10-27修订第16～19条。PlantMaterialRecipes保存不可变配方计划；PlantMaterialRecipeProvider使用原版配方生成器，并提供NeoForge燃料／堆肥数据映射的贡献入口。真实材料尚未注册，没有输出猜测ID的游戏配方，也没有在本轮执行datagen。

## 输入与配方

创建Materials时提供七种互不相同的正式物品ID：草杆捆、四种装饰毯、枯死原木、枯死茎。四种毯的枚举键为SCATTERED_SPORE_BALLS、TWIGS、FERN_FRONDS、FALLEN_LEAVES。必须齐全，不能用空气或同一物品充当多个材料。

配方计划固定六条，全部由正式ID派生唯一配方名：

- 草杆捆1个，在切石机选择散落孢子叶球／枯枝／蕨叶／落叶层毯，分别得到所选的3个；一次只选择一种结果。
- 枯死原木1个，切石得到枯死茎4个。
- 枯死茎4个，在工作台3×3四角摆放，中间及四边空，得到枯死原木1个；不用原版无法实现的“切石机一次消耗4个”。

每条配方具有相应原料的配方书解锁条件。所有物品均先通过HolderLookup解析，验证注册与输出堆叠容量后才输出配方；缺少任意一种会报错，不生成部分缺物品配方。

四种装饰毯的工作台原料／排列尚未给出，用户已明确先只做切石机，工作台后补。因此不生成四条相同草杆捆原料却输出不同毯的歧义工作台配方。旧“原木1→8枯枝”和“任意树叶切石→4叶球／落叶层”未加入。

## 数据生成接入

正式注册所有材料后，将PlantMaterialRecipeProvider(PackOutput, lookupFuture, materials)加入项目GatherData流程。它只生成原版recipe和相关解锁advancement，不注册运行时特殊配方序列化器。

项目统一DataMapProvider的gather(HolderLookup.Provider)内调用PlantMaterialRecipeProvider.contributeItemData(provider, registries, materials)，给四种毯添加：

- FurnaceFuel(300)，即原版三根木棍燃烧时间；1草杆捆→3毯合计900刻，与原大纲九木棍热值相符。
- Compostable(0.30F)，30%堆肥率，使用NeoForge默认村民行为配置。

数据映射必须由项目统一输出，保留已有泥炭burn_time=16000及其他条目。目前泥炭在src/main/resources/data/neoforge/data_maps/item/furnace_fuels.json中；未来激活完整datagen时，应先把该条目合入统一provider，再由一个源目录拥有这个JSON，避免src/main与src/generated同时生成同路径文件或覆盖原泥炭条目。当前不改动或删除该文件。

本接口不会直接修改原版全局燃料表或堆肥表，不在每tick重新登记，服务端数据包加载后由NeoForge应用数据映射。

## 验证边界

全项目源码使用本地可用缓存依赖独立javac编译通过，项目目标仍为Minecraft1.21.1／NeoForge21.1.228，未更改构建基线，也未运行Gradle。临时检查覆盖六条唯一配方、四毯1→3、原木与茎往返守恒、3×3四角正好四份、旧取消路线缺席、缺材料／空气／别名材料拒绝，以及官方FurnaceFuel和Compostable codec往返。

实际GatherData运行、生成JSON的游戏加载、配方书、工作台匹配、熔炉和自动堆肥需正式材料注册后验证，不能以这些基础生成接口宣称游戏内容已经可用。
