package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** 新叶次数由物种提案显式指定，下一轮等待仅在本轮世界成功后才抽取。 */
record PlantLeafRegrowthChange(String key, PlantLeafState leaf, PlantLeafData before,
        RandomSource random, Optional<String> retainedKey) implements PlantLeafStructureChange {
    @Override public java.util.Optional<PlantLeafData> expected() { return java.util.Optional.of(before); }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        if (!PlantLeafScheduler.canRun(owner) || !before.afterParts(parts).leaves().equals(before.leaves()))
            throw new IllegalArgumentException("Regrowth cannot change existing leaf resources");
        var after = retainedKey.isPresent() ? before.leaves().inheritRetained(retainedKey.get(), key, leaf).orElseThrow()
                : before.leaves().addNew(key, leaf).orElseThrow();
        var timers = retainedKey.isPresent()
                ? before.timers().finishInheritedRegrowth(before.leaves(), after, retainedKey.get(), key, owner.source(), random).orElseThrow()
                : before.timers().finishRegrowth(before.leaves(), after, key, owner.source(), random).orElseThrow();
        var next = new PlantLeafData(after, timers);
        next.validateOwner(owner, parts);
        return next;
    }
}
