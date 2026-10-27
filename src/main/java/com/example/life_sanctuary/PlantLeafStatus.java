package com.example.life_sanctuary;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Jade/调试用只读叶状态。只查已有索引及活动计时，不读方块、不加载区块、不启动任务。 */
public final class PlantLeafStatus {
    private PlantLeafStatus() {}
    public enum LookupReason { FOUND, NO_INDIVIDUAL, NO_LEAF_DATA, DATA_UNAVAILABLE }
    public enum Regrowth {
        APPLYING_STRUCTURE, RECOVERY_REQUIRED, WITHERING, SOURCE_DISABLED, PRUNED, ENVIRONMENT_PAUSED,
        FULL, NO_TIMER, WAITING, READY, MISSING_HANDLER
    }
    public record Shedding(int waiting, int ready, int failed, OptionalLong earliestRemainingTicks) {}
    public record Snapshot(UUID plantId, ResourceLocation species, PlantSource source, String stage,
            PlantLeafCounts counts, Regrowth regrowth, Optional<PlantLeafTimer> regrowthTimer,
            Shedding shedding, boolean hasHandler, Optional<PlantWitheringTimer> witheringTimer,
            boolean hasWitheringHandler) {}
    public record Lookup(LookupReason reason, Optional<Snapshot> snapshot) {}

    /** 主干、芽丛或叶任一已登记部件都能查看该株的汇总。 */
    public static Lookup at(ServerLevel level, BlockPos pos) {
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly()) return missing(LookupReason.DATA_UNAVAILABLE);
        var plant = store.atPart(pos).orElseGet(() -> store.atBase(pos).orElse(null));
        return plant == null ? missing(LookupReason.NO_INDIVIDUAL) : byId(store, plant.id());
    }

    public static Lookup byId(PlantIndividuals store, UUID id) {
        if (store.isReadOnly()) return missing(LookupReason.DATA_UNAVAILABLE);
        var plant = store.find(id).orElse(null);
        if (plant == null) return missing(LookupReason.NO_INDIVIDUAL);
        var data = store.leavesOf(id).orElse(null);
        var counts = store.leafCounts(id).orElse(null);
        if (data == null && counts == null) return missing(LookupReason.NO_LEAF_DATA);
        if (data == null || counts == null) return missing(LookupReason.DATA_UNAVAILABLE);
        int waiting = 0, ready = 0, failed = 0;
        long earliest = Long.MAX_VALUE;
        // 只遍历当前脱落任务，不遍历历史叶账本，也不复制坐标和资源键到快照。
        for (var timer : data.timers().shedding().values()) {
            if (timer.failed()) failed++;
            else {
                if (timer.ready()) ready++; else waiting++;
                earliest = Math.min(earliest, timer.remainingTicks());
            }
        }
        var timer = data.timers().regrowth().or(() -> data.timers().crownRegrowth().values().stream()
                .min(java.util.Comparator.comparingLong(PlantLeafTimer::remainingTicks)));
        boolean handler = PlantLeafHandlers.find(plant.species()) != null;
        Regrowth state;
        if (store.isApplyingStructure(id)) state = Regrowth.APPLYING_STRUCTURE;
        else if (failed > 0 || timer.filter(PlantLeafTimer::failed).isPresent()
                || data.timers().crownRegrowth().values().stream().anyMatch(PlantLeafTimer::failed)
                || plant.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))) state = Regrowth.RECOVERY_REQUIRED;
        else if (PlantWithering.isWithering(plant)) state = Regrowth.WITHERING;
        else if (!plant.source().canProgress()) state = Regrowth.SOURCE_DISABLED;
        else if (plant.pruned()) state = Regrowth.PRUNED;
        else if (!plant.environmentReasons().isEmpty()) state = Regrowth.ENVIRONMENT_PAUSED;
        else if (counts.freeLeafSlots() == 0) state = Regrowth.FULL;
        else if (timer.isEmpty()) state = Regrowth.NO_TIMER;
        else if (!timer.get().ready()) state = Regrowth.WAITING;
        else state = handler ? Regrowth.READY : Regrowth.MISSING_HANDLER;
        var shedding = new Shedding(waiting, ready, failed,
                earliest == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(earliest));
        return new Lookup(LookupReason.FOUND, Optional.of(new Snapshot(id, plant.species(), plant.source(), plant.stage(),
                counts, state, timer, shedding, handler, store.witheringTimer(id),
                PlantWitheringHandlers.find(plant.species()) != null)));
    }

    private static Lookup missing(LookupReason reason) { return new Lookup(reason, Optional.empty()); }
}
