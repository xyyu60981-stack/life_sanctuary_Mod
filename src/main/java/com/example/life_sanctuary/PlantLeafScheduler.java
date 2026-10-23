package com.example.life_sanctuary;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 独立叶复用区块时钟/到期队列；每刻不扫描全部植株或叶资源记录。 */
final class PlantLeafScheduler {
    private final PlantGrowthScheduler queue = new PlantGrowthScheduler(32);
    private record Binding(UUID plant, PlantLeafHandlers.Event event) {}
    private final Map<UUID, Binding> bindings = new HashMap<>();
    private final Map<UUID, Set<UUID>> byPlant = new HashMap<>();

    static boolean canRun(PlantIndividualState plant) {
        return plant.source().canProgress() && !plant.pruned() && plant.environmentReasons().isEmpty();
    }

    void update(PlantIndividualState plant, PlantLeafData data) {
        cancel(plant.id());
        if (data == null || !canRun(plant)) return;
        data.timers().regrowth().ifPresent(timer -> add(plant, timer, ""));
        data.timers().shedding().forEach((key, timer) -> add(plant, timer, key));
    }

    private void add(PlantIndividualState plant, PlantLeafTimer timer, String key) {
        if (timer.failed()) return;
        var event = new PlantLeafHandlers.Event(timer.kind(), key);
        UUID id = taskId(plant.id(), event);
        var binding = new Binding(plant.id(), event);
        var previous = bindings.putIfAbsent(id, binding);
        if (previous != null) throw new IllegalStateException("Leaf scheduler task identity collision");
        byPlant.computeIfAbsent(plant.id(), ignored -> new HashSet<>()).add(id);
        queue.updateSystem(id, plant.base(), timer.targetTicks(), timer.elapsedTicks());
    }

    void cancel(UUID plant) {
        var tasks = byPlant.remove(plant);
        if (tasks == null) return;
        for (var id : tasks) {
            queue.cancel(id);
            bindings.remove(id);
        }
    }

    PlantLeafData current(UUID plant, PlantLeafData saved) {
        if (saved == null || !byPlant.containsKey(plant)) return saved;
        Optional<PlantLeafTimer> regrowth = saved.timers().regrowth().map(timer -> current(plant, timer, ""));
        Map<String, PlantLeafTimer> shedding = new HashMap<>();
        saved.timers().shedding().forEach((key, timer) -> shedding.put(key, current(plant, timer, key)));
        return new PlantLeafData(saved.leaves(), new PlantLeafTimers(regrowth, shedding));
    }

    private PlantLeafTimer current(UUID plant, PlantLeafTimer timer, String key) {
        UUID id = taskId(plant, new PlantLeafHandlers.Event(timer.kind(), key));
        long elapsed = queue.currentElapsed(id, timer.elapsedTicks());
        return elapsed == timer.elapsedTicks() ? timer : timer.advance(elapsed - timer.elapsedTicks());
    }

    void tick(ServerLevel level, PlantIndividuals store) {
        if (store.isReadOnly() || store.isApplyingStructure()) return;
        queue.tickSystems(level, store::setDirty, id -> {
            var binding = bindings.get(id);
            if (binding == null) { queue.cancel(id); return; }
            var plant = store.find(binding.plant()).orElse(null);
            var leaves = store.leavesOf(binding.plant()).orElse(null);
            if (plant == null || leaves == null || !canRun(plant)) {
                cancel(binding.plant());
                return;
            }
            var event = binding.event();
            var timer = event.kind() == PlantLeafTimer.Kind.REGROWTH ? leaves.timers().regrowth().orElse(null)
                    : leaves.timers().shedding().get(event.leafKey());
            if (timer == null || !timer.ready()) return;
            var handler = PlantLeafHandlers.find(plant.species());
            if (handler == null) return;
            try {
                handler.onReady(level, store, plant, leaves, event);
            } catch (RuntimeException failure) {
                store.failLeafTimers(binding.plant());
                org.slf4j.LoggerFactory.getLogger(PlantLeafScheduler.class).error("Leaf callback failed for " + binding.plant(), failure);
            }
        });
    }

    private static UUID taskId(UUID plant, PlantLeafHandlers.Event event) {
        // 固定命名空间 + 固定长度植株 UUID + 类型 + 分隔后的完整键；不消费世界随机源。
        return UUID.nameUUIDFromBytes(("life_sanctuary:leaf/" + plant + "/" + event.kind().name()
                + "/" + event.leafKey()).getBytes(StandardCharsets.UTF_8));
    }
}
