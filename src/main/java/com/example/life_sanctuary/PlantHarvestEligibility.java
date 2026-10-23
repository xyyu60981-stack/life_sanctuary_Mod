package com.example.life_sanctuary;

import net.minecraft.resources.ResourceLocation;

/** 孢子／种子采集的共同年龄资格；不代替结构额度、袋子、授粉或成熟检测。 */
public final class PlantHarvestEligibility {
    private PlantHarvestEligibility() {}

    public static boolean isFullyAdult(ResourceLocation species, String stage) {
        var definition = PlantSpeciesRegistry.find(species).orElse(null);
        var metadata = definition == null ? null : definition.stages().get(stage);
        return metadata != null && metadata.fullyAdult();
    }

    public static boolean canCollectSporeOrSeed(PlantIndividualState plant) {
        return isFullyAdult(plant.species(), plant.stage());
    }

    /** 未成年首冠不能提前发放孢子可采额度；不凭零额度叶的外观推断成年。 */
    static boolean permitsInitialLeaves(PlantLeafData data, ResourceLocation species, String stage) {
        return isFullyAdult(species, stage)
                || data.leaves().leaves().values().stream().noneMatch(PlantLeafState::reservesFertileSlot);
    }
}
