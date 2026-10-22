package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Runtime-only per-chunk clocks. Save snapshots contain elapsed ticks, never world timestamps:
 * sleeping, /time changes and time spent outside ticking chunks cannot advance growth.
 */
final class PlantGrowthScheduler {
    private static final int MAX_CALLBACKS_PER_CHUNK = 16;
    private static final int MAX_CALLBACKS_PER_LEVEL = 128;
    private final Map<Long, Clock> clocks = new HashMap<>();
    private final Map<UUID, Task> tasks = new HashMap<>();
    private int cursor;
    private static final class Clock {
        long time;
        final TreeMap<Long, Map<UUID, Task>> due = new TreeMap<>();
        int members;
    }
    private record Task(UUID id, long chunk, long started, long elapsed, long target, long due) {}

    void update(PlantIndividualState state) {
        cancel(state.id());
        if (!PlantLifecycle.canRunTimer(state)) return;
        long key = ChunkPos.asLong(state.base().getX() >> 4, state.base().getZ() >> 4);
        Clock clock = clocks.computeIfAbsent(key, ignored -> new Clock());
        long delay = Math.max(1, state.remainingTicks());
        long due = delay > Long.MAX_VALUE - clock.time ? Long.MAX_VALUE : clock.time + delay;
        Task task = new Task(state.id(), key, clock.time, state.elapsedTicks(), state.targetTicks(), due);
        tasks.put(state.id(), task);
        clock.members++;
        clock.due.computeIfAbsent(due, ignored -> new HashMap<>()).put(state.id(), task);
    }

    void cancel(UUID id) {
        Task task = tasks.remove(id);
        if (task == null) return;
        Clock clock = clocks.get(task.chunk());
        Map<UUID, Task> bucket = clock.due.get(task.due());
        if (bucket != null) {
            bucket.remove(id);
            if (bucket.isEmpty()) clock.due.remove(task.due());
        }
        if (--clock.members == 0) clocks.remove(task.chunk());
    }

    PlantIndividualState current(PlantIndividualState state) {
        Task task = tasks.get(state.id());
        if (task == null) return state;
        Clock clock = clocks.get(task.chunk());
        long elapsed = task.elapsed() + Math.min(task.target() - task.elapsed(), clock.time - task.started());
        return state.withTimer(state.targetTicks(), elapsed);
    }

    void tick(ServerLevel level, PlantIndividuals store) {
        if (!level.tickRateManager().runsNormally()) return;
        int remainingCallbacks = MAX_CALLBACKS_PER_LEVEL;
        // Snapshot chunk keys, not all plants. Callbacks may create/remove chunk queues.
        var keys = new ArrayList<>(clocks.keySet());
        if (keys.isEmpty()) return;
        int start = Math.floorMod(cursor, keys.size());
        cursor = (start + 1) % keys.size();
        for (int offset = 0; offset < keys.size(); offset++) {
            long key = keys.get((start + offset) % keys.size());
            Clock clock = clocks.get(key);
            if (clock == null || !level.getChunkSource().isPositionTicking(key)) continue;
            clock.time++;
            store.setDirty(); // elapsed snapshots must be written on the next normal autosave
            int budget = MAX_CALLBACKS_PER_CHUNK;
            while (budget-- > 0 && remainingCallbacks > 0
                    && !clock.due.isEmpty() && clock.due.firstKey() <= clock.time) {
                remainingCallbacks--;
                var bucket = clock.due.firstEntry();
                Task task = bucket.getValue().values().iterator().next();
                bucket.getValue().remove(task.id());
                if (bucket.getValue().isEmpty()) clock.due.remove(bucket.getKey());
                var state = store.find(task.id()).orElse(null);
                if (state != null) {
                    var handler = PlantGrowthHandlers.find(state.species());
                    if (handler != null) {
                        try {
                            handler.onReady(level, store, state);
                        } catch (RuntimeException exception) {
                            // Stop this timer safely; do not repeat a partially applied world operation.
                            store.find(task.id()).ifPresent(current -> {
                                var reasons = new java.util.HashSet<>(current.environmentReasons());
                                reasons.add("growth_callback_error");
                                store.replace(current, current.withEnvironmentReasons(reasons));
                            });
                            org.slf4j.LoggerFactory.getLogger(PlantGrowthScheduler.class)
                                    .error("Plant growth callback failed for " + task.id(), exception);
                        }
                    }
                }
                // A successful adapter replaces/removes its task. Unchanged mature tasks retry slowly.
                if (tasks.get(task.id()) == task) {
                    Task retry = new Task(task.id(), key, task.started(), task.elapsed(),
                            task.target(), clock.time + 100);
                    tasks.put(task.id(), retry);
                    clock.due.computeIfAbsent(retry.due(), ignored -> new HashMap<>()).put(retry.id(), retry);
                }
                if (clocks.get(key) != clock) break;
            }
        }
    }
}
