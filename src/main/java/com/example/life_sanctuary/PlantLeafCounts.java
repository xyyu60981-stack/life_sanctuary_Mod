package com.example.life_sanctuary;

/** 派生计数快照，不写入 NBT；按叶资源变化重建，悬停查询不遍历历史资源键。 */
public record PlantLeafCounts(int leafLimit, int fertileLimit, int presentLeaves, int reservedFertileSlots,
        int detachedFertileSlots, int exhaustedPresentLeaves, long remainingCollections) {
    public PlantLeafCounts {
        if (leafLimit < 1 || fertileLimit < 0 || fertileLimit > leafLimit || presentLeaves < 0 || presentLeaves > leafLimit
                || reservedFertileSlots < 0 || reservedFertileSlots > fertileLimit || detachedFertileSlots < 0
                || detachedFertileSlots > reservedFertileSlots || exhaustedPresentLeaves < 0
                || exhaustedPresentLeaves > presentLeaves || remainingCollections < 0)
            throw new IllegalArgumentException("Invalid derived leaf counts");
    }
    public int freeLeafSlots() { return leafLimit - presentLeaves; }
    public int freeFertileSlots() { return fertileLimit - reservedFertileSlots; }

    static PlantLeafCounts from(PlantLeafSet leaves) {
        int present = 0, reserved = 0, detached = 0, exhausted = 0;
        long remaining = 0;
        for (var leaf : leaves.leaves().values()) {
            if (leaf.present()) present++;
            if (leaf.reservesFertileSlot()) {
                reserved++;
                if (!leaf.present()) detached++;
            }
            if (leaf.present() && leaf.exhausted()) exhausted++;
            remaining = Math.addExact(remaining, leaf.remainingCollections());
        }
        return new PlantLeafCounts(leaves.leafLimit(), leaves.fertileLimit(), present, reserved, detached, exhausted, remaining);
    }
}
