# 苏铁种子外种皮接入与验证边界

第34条只增加种子的视觉差异：采集默认有外种皮，带皮／去皮共用同物种的同一个物品ID和名称，都可种；幼苗不再显示种皮。当前未注册正式苏铁种子／种子方块，本批提供共享接口、通用配方及阶段事务保护，不以假物种演示完整生长。

## 物种接入

实际种子方块实现CycadSeedAppearance，提供真实species、seedStage和24位RGB coatColor；StateDefinition添加SEED_COAT，默认seed_coat=true。为其注册一个CycadSeedItem，构造器验证契约，不另注册peeled_seed。基底、形状、生长时长、活体登记、种群、环境检测仍由物种适配器提供；默认BlockItem放置不代表已经建立完整活体。

成熟种子采集／死体抢救的物品发放适配器使用item.collected(count)创建带皮新栈，count为1～原生栈上限，更多产量由发放方分栈；不得绕过既有成熟额度、成年资格或抢救台账先发物品。尚未接入实际采集物品发放，不声称仅有此工厂就已完成采集。

掉落／创造选取使用CycadSeedCoat.fromState，保持种下的去皮状态；此函数不自行发物品，不替代工具或收回规则。采集箱仍需具体物种加入collector_contents白名单；按物品ID的名单自然同时接受两种外观。

## 去皮与数据

实际注册serializer及recipe资源life_sanctuary:cycad_seed_peeling。任意合成窗口中只占一个非空格、放带皮CycadSeedItem，即可得1个去皮种子。输入可是一栈，消耗及Shift连续合成由原版负责，每次扣1；其他种子、已去皮、损坏种皮字段及多个非空格不匹配。种皮直接消失，无余物；getRemainingItems为空。

原生BLOCK_STATE的seed_coat字段保存状态：缺字段／true为带皮，false为去皮，其他字符串拒绝。assemble复制1个种子，仅更新此字段，保留原ID、名称、自定义数据及其他组件／block state字段，不改原输入。原生放置应用组件，两种状态使用同一BlockItem.place及基底判定；本类不按种皮分流生态条件。未增加PlantIndividualState字段或另一份生长计时。

## 幼苗与美术

幼苗由PlantStageGrowth正常替换种子方块。共享阶段入口与底层PlantStructureCommitter都验证种子外观与目标物种／seedStage一致：幼苗阶段若仍使用CycadSeedAppearance种子方块则拒绝，成功长成合法幼苗才移除种皮。其他方块不受此检查影响；自然生成和绕过共享入口的物种自有写入仍须正确适配。

客户端模型谓词life_sanctuary:seed_coat带皮1、去皮0，作者可设裸种子为默认、阈值1用带皮模型。Blockstate按seed_coat=true／false选静态模型；仅外种皮面／层用tintindex=0，染色器按物种coatColor着色，其他面／层返回白色。没有擅自指定具体物种颜色或制作模型。

客户端初始化／颜色注册阶段扫描实际注册项一次；世界显示只靠方块状态，无新增方块实体、ticker、网络包或每刻扫描。

## 验证

全项目以本地缓存依赖独立javac通过，未运行Gradle。临时检查覆盖默认带皮、显式true／false、去皮前后原始组件不可变、其他block state属性保留、非法值拒绝及原生组件CODEC往返。recipe JSON及变更空白检查通过。

正式种子尚未注册，未执行真实合成预览／Shift批量、全物品组件往返、种子取回、两种状态的基底播种、活体初始化、幼苗世界事务、颜色材质或专用服务器验证。本批不等于苏铁具体物种已经完成；这些都需内容接入后联调。
