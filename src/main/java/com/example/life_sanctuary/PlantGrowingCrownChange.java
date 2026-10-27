package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/** 未成年营养叶冠随阶段重建；成年后禁用，不是成年资源刷新入口。 */
record PlantGrowingCrownChange(PlantLeafData before, PlantIndividualState target,
        PlantLeafData crown, RandomSource random) implements PlantLeafStructureChange {
    static boolean eligible(PlantIndividualState owner, PlantLeafData before) {
        return !PlantHarvestEligibility.canCollectSporeOrSeed(owner)
                && before.leaves().leaves().values().stream().noneMatch(PlantLeafState::fertile)
                && before.timers().regrowth().filter(PlantLeafTimer::failed).isEmpty()
                && before.timers().crownRegrowth().values().stream().noneMatch(PlantLeafTimer::failed)
                && before.timers().shedding().isEmpty();
    }

    static boolean permitsTarget(net.minecraft.resources.ResourceLocation species, String stage, PlantLeafData crown) {
        var definition = PlantSpeciesRegistry.find(species).orElse(null);
        if (definition == null || !definition.stages().containsKey(stage)) return false;
        return PlantHarvestEligibility.isFullyAdult(species, stage)
                || crown.leaves().leaves().values().stream().noneMatch(PlantLeafState::fertile);
    }

    static PlantLeafData merge(PlantLeafData before, PlantLeafData crown) {
        var leaves = new HashMap<String, PlantLeafState>();
        before.leaves().leaves().forEach((key, leaf) -> leaves.put(key, leaf.withoutStructure()));
        crown.leaves().leaves().forEach((key, leaf) -> {
            if (leaves.putIfAbsent(key, leaf) != null) throw new IllegalArgumentException("Growing crown cannot reuse historical leaf keys");
        });
        return new PlantLeafData(new PlantLeafSet(crown.leaves().leafLimit(), crown.leaves().fertileLimit(),
                leaves, crown.leaves().allowedRoles(), crown.leaves().crowns()), PlantLeafTimers.EMPTY);
    }

    static Map<BlockPos, String> nonLeafParts(PlantLeafData before, Map<BlockPos, String> owned) {
        var retained = new HashMap<>(owned);
        var activeLeaves = new PlantLeafPartIndex(before.leaves());
        // 历史只遍历一次，之后按部件角色查索引，避免“部件数 × 历史叶数”。
        retained.entrySet().removeIf(entry -> activeLeaves.keyForRole(entry.getValue()).isPresent());
        return Map.copyOf(retained);
    }

    /** 旧叶角色仍留在目标中时，必须归属新冠活叶，不能变成无账本的叶外形。 */
    static boolean coversOldLeafShapes(PlantLeafData before, PlantLeafData crown, Map<BlockPos, String> targetParts) {
        var oldLeaves = new PlantLeafPartIndex(before.leaves());
        var newLeaves = new PlantLeafPartIndex(crown.leaves());
        return targetParts.values().stream().noneMatch(role -> oldLeaves.keyForRole(role).isPresent()
                && newLeaves.keyForRole(role).isEmpty());
    }

    @Override public Optional<PlantLeafData> expected() { return Optional.of(before); }
    @Override public PlantIndividualState nextOwner(PlantIndividualState owner) {
        if (!eligible(owner, before) || !permitsTarget(target.species(), target.stage(), crown))
            throw new IllegalArgumentException("Not a juvenile crown growth transition");
        return target;
    }
    @Override public PlantLeafData afterParts(PlantIndividualState owner, Map<BlockPos, String> parts) {
        if (!target.equals(owner)) throw new IllegalArgumentException("Wrong growing crown owner");
        if (!coversOldLeafShapes(before, crown, parts)) throw new IllegalArgumentException("Untracked old leaf shape in target crown");
        var merged = merge(before, crown);
        // 营养叶历史只保留资源键；新冠的支撑和额度已在世界写入前校验。
        var timers = new PlantLeafInitializationChange(crown, random).afterParts(owner, parts).timers();
        var next = new PlantLeafData(merged.leaves(), timers);
        next.validateOwner(owner, parts);
        return next;
    }
}
