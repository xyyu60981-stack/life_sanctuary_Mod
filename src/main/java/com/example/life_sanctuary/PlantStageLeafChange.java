package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** 阶段与首冠一起提交的封闭凭据；不修改已有叶资源。 */
record PlantStageLeafChange(PlantIndividualState target, PlantLeafData initial, RandomSource random)
        implements PlantLeafStructureChange {
    PlantStageLeafChange {
        java.util.Objects.requireNonNull(target);
        java.util.Objects.requireNonNull(initial);
        java.util.Objects.requireNonNull(random);
    }
    @Override public Optional<PlantLeafData> expected() { return Optional.empty(); }
    @Override public PlantIndividualState nextOwner(PlantIndividualState before) { return target; }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        if (!target.equals(owner)) throw new IllegalArgumentException("Wrong crown stage target");
        return new PlantLeafInitializationChange(initial, random).afterParts(owner, parts);
    }
}
