package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** 同一 SavedData 内一起保存的叶资源和计时，不将次数再复制到通用 harvests。 */
public record PlantLeafData(PlantLeafSet leaves, PlantLeafTimers timers) {
    public PlantLeafData {
        Objects.requireNonNull(leaves);
        Objects.requireNonNull(timers);
        if (timers.regrowth().isPresent() && leaves.freeLeafSlots() == 0)
            throw new IllegalArgumentException("Full canopy cannot retain a regrowth timer");
        if (timers.regrowth().isPresent() && !leaves.crowns().isEmpty()) throw new IllegalArgumentException("Grouped canopy cannot use a global timer");
        for (var key : timers.crownRegrowth().keySet()) {
            if (!leaves.crowns().containsKey(key) || leaves.freeCrownSlots(key) == 0) throw new IllegalArgumentException("Invalid crown regrowth timer");
        }
        for (var key : timers.shedding().keySet()) {
            var leaf = leaves.leaves().get(key);
            if (leaf == null || !leaf.present() || !leaf.exhausted())
                throw new IllegalArgumentException("Shedding timer has no attached exhausted leaf");
        }
    }

    void validateOwner(PlantIndividualState owner, Map<BlockPos, String> parts) {
        if (!owner.source().canProgress() && (timers.regrowth().isPresent() || !timers.shedding().isEmpty() || !timers.crownRegrowth().isEmpty()))
            throw new IllegalArgumentException("Non-player leaf data cannot run regeneration cycles");
        leaves.leaves().forEach((key, leaf) -> {
            if (owner.harvests().containsKey(key) || owner.reproduction().containsKey(key))
                throw new IllegalArgumentException("Leaf resources cannot also exist in generic quota maps");
            if (leaf.present() && !Objects.equals(parts.get(leaf.anchor()), leaf.role()))
                throw new IllegalArgumentException("Attached leaf has no matching structural ownership");
        });
        for (var entry : leaves.crowns().entrySet()) {
            var crown = entry.getValue();
            if ((leaves.freeCrownSlots(entry.getKey()) < crown.roles().size() || timers.crownRegrowth().containsKey(entry.getKey()))
                    && !parts.containsKey(crown.center())) throw new IllegalArgumentException("Crown has no structural ownership");
        }
    }

    /** 已成功提交部件变化后同步消失状态，切除不释放尚有次数的可育名额。 */
    PlantLeafData afterParts(Map<BlockPos, String> parts) {
        Map<String, PlantLeafState> updated = new HashMap<>(leaves.leaves());
        updated.replaceAll((key, leaf) -> leaf.present() && !Objects.equals(parts.get(leaf.anchor()), leaf.role())
                ? leaf.withoutStructure() : leaf);
        if (updated.equals(leaves.leaves())) return this;
        var next = new PlantLeafSet(leaves.leafLimit(), leaves.fertileLimit(), updated, leaves.allowedRoles(), leaves.crowns());
        return new PlantLeafData(next, timers.afterStructureChange(next));
    }
}
