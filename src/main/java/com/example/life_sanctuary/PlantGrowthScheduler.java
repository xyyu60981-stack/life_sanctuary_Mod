package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Runtime-only per-chunk clocks. Save snapshots contain elapsed ticks, never world timestamps:
 * sleeping, /time changes and time spent outside ticking chunks cannot advance growth.
 */
final class PlantGrowthScheduler {
    private static final int MAX_CALLBACKS_PER_CHUNK = 16;
    private final int maxCallbacksPerLevel;
    private final Map<Long, Clock> clocks = new HashMap<>();
    private final Map<UUID, Task> tasks = new HashMap<>();
    private int cursor;
    PlantGrowthScheduler() { this(128); }
    PlantGrowthScheduler(int maxCallbacksPerLevel) {
        if (maxCallbacksPerLevel <= 0) throw new IllegalArgumentException("Invalid callback budget");
        this.maxCallbacksPerLevel = maxCallbacksPerLevel;
    }
    private static final class Clock {
        long time;
        final TreeMap<Long, Map<UUID, Task>> due = new TreeMap<>();
        int members;
    }
    private record Task(UUID id, long chunk, long started, long elapsed, long target, long due) {}

    void update(PlantIndividualState state) {
        cancel(state.id());
        if (!PlantLifecycle.canRunTimer(state)) return;
        schedule(state, state.targetTicks(), state.elapsedTicks());
    }

    void updateWithering(PlantIndividualState state, PlantWitheringTimer timer) {
        cancel(state.id());
        if (!timer.failed()) schedule(state, timer.targetTicks(), timer.elapsedTicks());
    }

    private void schedule(PlantIndividualState state, long target, long elapsed) {
        schedule(state.id(), state.base(), target, elapsed);
    }

    /** 独立地下系统使用自己的调度实例，不创建伪个体，也不与个体 UUID 队列混用。 */
    void updateSystem(UUID id, BlockPos base, long target, long elapsed) {
        if (target <= 0 || elapsed < 0 || elapsed > target) throw new IllegalArgumentException("Invalid system timer");
        cancel(id);
        schedule(id, base, target, elapsed);
    }

    private void schedule(UUID id, BlockPos base, long target, long elapsed) {
        long key = ChunkPos.asLong(base.getX() >> 4, base.getZ() >> 4);
        Clock clock = clocks.computeIfAbsent(key, ignored -> new Clock());
        long delay = Math.max(1, target - elapsed);
        long due = delay > Long.MAX_VALUE - clock.time ? Long.MAX_VALUE : clock.time + delay;
        Task task = new Task(id, key, clock.time, elapsed, target, due);
        tasks.put(id, task);
        clock.members++;
        clock.due.computeIfAbsent(due, ignored -> new HashMap<>()).put(id, task);
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
        if (!tasks.containsKey(state.id())) return state;
        return state.withTimer(state.targetTicks(), currentElapsed(state.id(), state.elapsedTicks()));
    }

    long currentElapsed(UUID id, long fallback) {
        Task task = tasks.get(id);
        if (task == null) return fallback;
        Clock clock = clocks.get(task.chunk());
        long elapsed = task.elapsed() + Math.min(task.target() - task.elapsed(), clock.time - task.started());
        return elapsed;
    }

    void tick(ServerLevel level, PlantIndividuals store) {
        tick(level, store, false);
    }

    void tickWithering(ServerLevel level, PlantIndividuals store) {
        tick(level, store, true);
    }

    private void tick(ServerLevel level, PlantIndividuals store, boolean withering) {
        tick(level, store, withering, store::setDirty, null);
    }

    void tickSystems(ServerLevel level, Runnable dirty, java.util.function.Consumer<UUID> onReady) {
        tick(level, null, false, dirty, java.util.Objects.requireNonNull(onReady));
    }

    private void tick(ServerLevel level, PlantIndividuals store, boolean withering,
            Runnable dirty, java.util.function.Consumer<UUID> systemReady) {
        if (!level.tickRateManager().runsNormally()) return;
        int remainingCallbacks = maxCallbacksPerLevel;
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
            dirty.run(); // elapsed snapshots must be written on the next normal autosave
            int budget = MAX_CALLBACKS_PER_CHUNK;
            while (budget-- > 0 && remainingCallbacks > 0
                    && !clock.due.isEmpty() && clock.due.firstKey() <= clock.time) {
                remainingCallbacks--;
                var bucket = clock.due.firstEntry();
                Task task = bucket.getValue().values().iterator().next();
                bucket.getValue().remove(task.id());
                if (bucket.getValue().isEmpty()) clock.due.remove(bucket.getKey());
                if (systemReady != null) {
                    systemReady.accept(task.id());
                } else {
                    var state = store.find(task.id()).orElse(null);
                    if (state == null) {
                        cancel(task.id());
                    } else if (withering) {
                        if (PlantWithering.requiresRecovery(state)) store.failWithering(task.id());
                        var handler = PlantWithering.requiresRecovery(state) ? null : PlantWitheringHandlers.find(state.species());
                        if (handler != null) {
                            try {
                                handler.onReady(level, store, state);
                                store.find(task.id()).filter(PlantWithering::requiresRecovery)
                                        .ifPresent(ignored -> store.failWithering(task.id()));
                            } catch (RuntimeException exception) {
                                store.failWithering(task.id());
                                org.slf4j.LoggerFactory.getLogger(PlantGrowthScheduler.class)
                                        .error("Plant withering callback failed for " + task.id(), exception);
                            }
                        }
                    } else {
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
