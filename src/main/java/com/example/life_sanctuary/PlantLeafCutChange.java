package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** 手工切叶只移除外形，不扣采集次数；人工补叶等待与结构移除一起提交。 */
record PlantLeafCutChange(String key, PlantLeafData before, RandomSource random) implements PlantLeafStructureChange {
    @Override public java.util.Optional<PlantLeafData> expected() { return java.util.Optional.of(before); }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        var old = before.leaves().leaves().get(key);
        if (old == null || !old.present()) throw new IllegalArgumentException("Leaf is already absent");
        var after = before.leaves().replace(key, old, old.withoutStructure()).orElseThrow();
        if (!before.afterParts(parts).leaves().equals(after))
            throw new IllegalArgumentException("Cut must remove exactly its own leaf");
        var timers = before.timers().afterStructureChange(after);
        if (owner.source().canProgress()) timers = timers.startRegrowth(after, owner.source(), random).orElseThrow();
        var next = new PlantLeafData(after, timers);
        next.validateOwner(owner, parts);
        return next;
    }
}
