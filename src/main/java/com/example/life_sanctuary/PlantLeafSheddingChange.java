package com.example.life_sanctuary;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** 包内脱落提交凭据；补叶时长在世界写入成功后的元数据提交中才抽取。 */
record PlantLeafSheddingChange(String key, PlantLeafData before, RandomSource random) implements PlantLeafStructureChange {
    @Override public java.util.Optional<PlantLeafData> expected() { return java.util.Optional.of(before); }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        var old = before.leaves().leaves().get(key);
        if (!PlantLeafScheduler.canRun(owner) || old == null || !old.present() || !old.exhausted())
            throw new IllegalArgumentException("Leaf cannot shed");
        var after = before.leaves().replace(key, old, old.withoutStructure()).orElseThrow();
        if (!before.afterParts(parts).leaves().equals(after))
            throw new IllegalArgumentException("Shedding must remove exactly its own leaf");
        var timers = before.timers().finishShedding(before.leaves(), after, key).orElseThrow();
        // 已有整株等待继续原进度，不因第二片叶脱落重新计时。
        timers = timers.regrowthAfterLoss(after, old.role(), owner.source(), random, false).orElseThrow();
        var next = new PlantLeafData(after, timers);
        next.validateOwner(owner, parts);
        return next;
    }
}
