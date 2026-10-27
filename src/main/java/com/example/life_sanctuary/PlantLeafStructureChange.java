package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;

/** 封闭的叶结构提交策略，不开放任意改写叶资源的回调。 */
sealed interface PlantLeafStructureChange permits PlantLeafSheddingChange, PlantLeafRegrowthChange, PlantLeafRegrowthBatchChange, PlantLeafCutChange, PlantBudLossChange, PlantLeafInitializationChange, PlantStageLeafChange, PlantGrowingCrownChange {
    java.util.Optional<PlantLeafData> expected();
    PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts);
    default PlantIndividualState nextOwner(PlantIndividualState before) { return before; }
}
