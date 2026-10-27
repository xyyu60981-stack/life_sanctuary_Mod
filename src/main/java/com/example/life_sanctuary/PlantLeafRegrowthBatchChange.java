package com.example.life_sanctuary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** 一次世界事务补齐当前芽丛账本的全部缺叶，保留逐叶可育判断及旧额度继承。 */
record PlantLeafRegrowthBatchChange(PlantLeafData before, List<PlantLeafRegrowth.Addition> additions)
        implements PlantLeafStructureChange {
    PlantLeafRegrowthBatchChange { additions = List.copyOf(additions); }
    @Override public Optional<PlantLeafData> expected() { return Optional.of(before); }

    Optional<String> crownKey() {
        return additions.isEmpty() ? Optional.empty() : before.leaves().crownOf(additions.getFirst().leaf().role());
    }

    PlantLeafSet proposal(PlantIndividualState owner) {
        if (!PlantLeafScheduler.canRun(owner) || additions.isEmpty()
                || before.timers().regrowthFor(before.leaves(), additions.getFirst().leaf().role()).filter(PlantLeafTimer::ready).isEmpty()
                || additions.size() != crownKey().map(before.leaves()::freeCrownSlots).orElseGet(before.leaves()::freeLeafSlots))
            throw new IllegalArgumentException("Regrowth batch must fill every missing leaf after the timer");
        var leaves = before.leaves();
        for (var addition : additions) {
            var leaf = addition.leaf();
            if (!before.leaves().crownOf(leaf.role()).equals(crownKey())) throw new IllegalArgumentException("Mixed crowns in regrowth batch");
            if (owner.harvests().containsKey(addition.key()) || owner.reproduction().containsKey(addition.key())
                    || leaf.fertile() && leaf.remainingCollections() == 0
                    || leaf.reservesFertileSlot() && !PlantHarvestEligibility.canCollectSporeOrSeed(owner))
                throw new IllegalArgumentException("Invalid regrowth leaf resource");
            if (addition.retainedKey().isPresent())
                leaves = leaves.inheritRetained(addition.retainedKey().get(), addition.key(), leaf).orElseThrow();
            else {
                if (leaf.fertile() && PlantLeafFertility.retainedKey(leaves).isPresent())
                    throw new IllegalArgumentException("Fertile regrowth must inherit retained quota first");
                leaves = leaves.addNew(addition.key(), leaf).orElseThrow();
            }
        }
        return leaves;
    }

    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        if (!before.afterParts(parts).leaves().equals(before.leaves()))
            throw new IllegalArgumentException("Regrowth batch changed old leaf structures");
        var leaves = proposal(owner);
        var next = new PlantLeafData(leaves, before.timers().completeCrown(leaves, crownKey()));
        next.validateOwner(owner, parts);
        return next;
    }
}
