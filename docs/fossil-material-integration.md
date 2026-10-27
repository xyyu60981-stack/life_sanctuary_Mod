# 第28条：煤化与印痕方块

三类方块基础和煤核接入已实现。正式方块／物品ID、纹理及模型尚未注册，不以通用原木或煤矿石冒充古植物材料。用户确认采掘为合适的镐直接回收本体，精准也回收本体，时运不增加数量。

## 内容作者的对应表

| 用户材料 | 方块类 | 状态与模型 |
| --- | --- | --- |
| 煤化鳞木原木、煤化封印木原木、煤化芦木纤维块 | FullCoalifiedBlock | axis三个状态，完整实体方块 |
| 煤化广德木茎、通用煤化茎 | EmbeddedCoalifiedBlock | axis三个方向×matrix石头／深板岩，两种宿主背景 |
| 印痕轮叶石头、印痕鳞木石头、印痕芦木石头等 | PlantImprintBlock | facing六面，完整石头方块上的古植物印痕 |

注册时给Properties及稳定ResourceLocation标本键，并为方块注册普通BlockItem。标本键表示对应化石原料类型，可包含材料／部位信息；通用煤化茎也可有自己的通用标本键，不强求存在对应活植物物种。不得随翻译名、贴图或石头／深板岩背景变化而改键。

方块均无方块实体、活体身份或tick。完整类三个轴向、镶嵌类六种组合、印痕类六面，均使用静态模型；不为每个方向／背景再建立独立活动对象。原木／纤维及镶嵌轴向沿用原版旋转行为，印痕面实现旋转和镜像。更具体的叶形／印痕图案及面内贴图方向由模型作者处理。

## 采掘、回收与基质

构造属性启用requiresCorrectToolForDrops；getDrops同时检查minecraft:pickaxes物品标签及isCorrectToolForDrops的实际方块判定。合适镐固定产本体1个，不读取时运／精准等级；错误工具或无工具不给本体。工具等级由正式采掘标签决定，未擅自指定铁／钻石门槛。其他模组镐须正确声明镐标签和对应采掘能力。

FossilMaterialTags数据provider接实际已注册Block列表，输出minecraft:mineable/pickaxe和life_sanctuary:fossil_materials；镐标签还保留已注册的化石切片机，材料标签仅包含真正的化石原料。激活datagen前必须为每个原料方块注册真实BlockItem；否则provider拒绝，不输出部分假材料标签。目前main资源已有仅含切片机的镐标签；激活本provider时应迁移合并为唯一来源，避免main／generated同路径覆盖并保留切片机条目。

EmbeddedCoalifiedBlock回收及创造选取通过原版BLOCK_STATE组件保存matrix，使用普通BlockItem放置时应用该组件，深板岩背景不会退回默认石头背景。axis按正常原木放置选择，不强制保留旧朝向。物品模型也需按matrix组件做相应展示，不能只画放置模型却声称库存材质已完成。

未增加燃料、燃烧、掉煤炭、经验或切石产量；这些不是本条用户已给定内容。煤化材料与前面的炭化／燃尽方块是不同类，不能通过标签混作可传火材料。

## 连接煤核

CoalCoreFeature.Material现在要求实际状态的方块实现FossilMaterial，且宿主匹配：

- 完整煤化块可作为石头和深板岩中的同一种古植物残材。
- 镶嵌状态的matrix必须与宿主一致；不能拿石头背景版本塞进深板岩。
- 印痕只接受石头宿主，deepslate选项应空缺，不凭空增加深板岩印痕。
- 两个宿主版本若同时提供，必须具有相同标本键和形态；不能随地下宿主变化把鳞木改成封印木，或把完整块改成镶嵌茎。

旋转后再检查类型、宿主、无流体和无实体；原coal_blocks标签准入仍有效。误把普通木材、炭化材或其他块加入标签也不能绕过化石类型校验。不会根据宿主自动替换成未注册的材料。

## 连接切片机

FossilMaterial.fossil返回不可变Specimen(id, form)，Form为full_coalified、embedded_coalified、stone_imprint，具有CODEC。完整和印痕即使使用同一植物标本键，也保持不同形态，供下一项切片配方区分。

FossilMaterial.identify(ItemStack)只读实际BlockItem及其方块元数据，并解析原版matrix组件，返回Sample。它不相信物品自报的Species CustomData，不扣材料、不创建输出、不选择切片数量。切片机还需在服务端校验输入槽、配方、操作完成和原子消耗；本接口不代替第29条加工事务。

## 验证边界

全部项目源码独立javac并开启弃用检查通过，未运行Gradle。规则检查覆盖三种形态、石头／深板岩宿主匹配、不同标本／形态配对拒绝、固定一份采掘规则、标本CODEC往返及原版BlockItemStateProperties组件的深板岩值往返；煤核几何和回退规则也已回归。

未运行真实采掘、工具耐久、创造选取、掉落物再放置、物品模型或标签datagen。正式材料接入后需在游戏验证标签生效、正确工具及背景材质保留，再进行切片机联调。
