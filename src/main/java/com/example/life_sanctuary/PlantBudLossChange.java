package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** 芽丛损毁的封闭提交策略，不发资源、不启动补叶，也不重置已有枯死计时。 */
record PlantBudLossChange(PlantLeafData before, long duration) implements PlantLeafStructureChange {
    PlantBudLossChange {
        java.util.Objects.requireNonNull(before);
        if (duration <= 0) throw new IllegalArgumentException("Bud loss needs an explicit withering duration");
    }
    @Override public PlantIndividualState nextOwner(PlantIndividualState before) {
        if (PlantWithering.isWithering(before)) throw new IllegalArgumentException("Bud loss already started");
        var reasons = new HashSet<>(before.environmentReasons());
        reasons.add(PlantWithering.BUD_CLUSTER_DESTROYED);
        return before.withEnvironmentReasons(reasons);
    }
    @Override public java.util.Optional<PlantLeafData> expected() { return java.util.Optional.of(before); }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        if (!PlantWithering.isWithering(owner)) throw new IllegalArgumentException("Missing bud loss state");
        var after = before.afterParts(parts).leaves();
        if (after.presentLeaves() != 0) throw new IllegalArgumentException("Bud loss must remove all live leaves");
        var next = new PlantLeafData(after, PlantLeafTimers.EMPTY);
        next.validateOwner(owner, parts);
        return next;
    }
}
