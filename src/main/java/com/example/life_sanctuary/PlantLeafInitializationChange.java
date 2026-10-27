package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** 首次叶结构与账本共同提交；空快照表示账本必须尚不存在。 */
record PlantLeafInitializationChange(PlantLeafData initial, RandomSource random) implements PlantLeafStructureChange {
    PlantLeafInitializationChange {
        java.util.Objects.requireNonNull(initial);
        java.util.Objects.requireNonNull(random);
        if (!initial.timers().equals(PlantLeafTimers.EMPTY))
            throw new IllegalArgumentException("Initialization cannot import leaf timers");
    }
    @Override public Optional<PlantLeafData> expected() { return Optional.empty(); }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        // 先校验完整结构，再抽取等待；预检失败、重复初始化和满冠均不消耗随机数。
        initial.validateOwner(owner, parts);
        if (!PlantHarvestEligibility.permitsInitialLeaves(initial, owner.species(), owner.stage()))
            throw new IllegalArgumentException("Immature crown cannot receive spore harvest quota");
        var timers = initial.timers();
        if (owner.source().canProgress() && initial.leaves().freeLeafSlots() > 0) {
            if (initial.leaves().crowns().isEmpty()) timers = timers.startRegrowth(initial.leaves(), owner.source(), random).orElseThrow();
            else for (var key : initial.leaves().crowns().keySet().stream().sorted().toList()) {
                if (initial.leaves().freeCrownSlots(key) > 0)
                    timers = timers.regrowthAfterLoss(initial.leaves(), initial.leaves().crowns().get(key).roles().stream().sorted().findFirst().orElseThrow(),
                            owner.source(), random, false).orElseThrow();
            }
        }
        var next = new PlantLeafData(initial.leaves(), timers);
        next.validateOwner(owner, parts);
        return next;
    }
}
