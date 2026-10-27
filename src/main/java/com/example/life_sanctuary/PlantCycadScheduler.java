package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** 注册苏铁的雌性花期复用区块活动时钟与到期队列，不逐刻扫描种群。 */
final class PlantCycadScheduler {
    private record Binding(long round, int attempt) {}
    private final PlantGrowthScheduler queue = new PlantGrowthScheduler(32);
    private final Map<UUID, Binding> bindings = new HashMap<>();
    PlantFlowerRound current(UUID id, PlantFlowerRound saved) {
        var binding = bindings.get(id);
        if (saved == null || binding == null || binding.round() != saved.id() || binding.attempt() != saved.cycadAttempts()) return saved;
        long elapsed = queue.currentElapsed(id, saved.cycadElapsedTicks());
        return elapsed == saved.cycadElapsedTicks() ? saved : new PlantFlowerRound(saved.id(), saved.active(), saved.failed(), saved.cycadAttempts(), elapsed);
    }
    void update(PlantIndividuals store, PlantIndividualState plant) {
        var raw = store.rawFlowerRound(plant.id());
        var round = current(plant.id(), raw);
        cancel(plant.id());
        if (round == null) return;
        store.captureCycadClock(plant.id(), raw, round);
        if (PlantCycadRules.find(plant.species()) == null || !plant.source().canFinishCurrentCycle()
                || plant.pruned() || !plant.environmentReasons().isEmpty()
                || !PlantHarvestEligibility.canCollectSporeOrSeed(plant) || !round.allowsPollenReception()
                || round.cycadAttempts() >= PlantFlowerRound.CYCAD_MAX_ATTEMPTS || store.cycadFlowerKey(plant.id()).isEmpty()) return;
        bindings.put(plant.id(), new Binding(round.id(), round.cycadAttempts()));
        queue.updateSystem(plant.id(), plant.base(), PlantFlowerRound.CYCAD_INTERVAL_TICKS, round.cycadElapsedTicks());
    }
    void cancel(UUID id) { queue.cancel(id); bindings.remove(id); }
    void tick(ServerLevel level, PlantIndividuals store) {
        if (store.isReadOnly() || store.isApplyingStructure()) return;
        queue.tickSystems(level, store::setDirty, id -> {
            var plant = store.find(id).orElse(null);
            var round = store.flowerRound(id).orElse(null);
            var key = store.cycadFlowerKey(id);
            var profile = plant == null ? null : PlantCycadRules.find(plant.species());
            if (plant == null || round == null || profile == null || key.isEmpty() || !round.cycadReady()
                    || plant.pruned() || !plant.environmentReasons().isEmpty()) { cancel(id); return; }
            try {
                PlantNaturalPollination.attemptCycad(level, store, plant, key.get(), round.id(),
                        profile.eligibleDonor(), profile.yield(), level.getRandom());
            } catch (RuntimeException failure) {
                cancel(id);
                store.find(id).ifPresent(current -> {
                    var reasons = new java.util.HashSet<>(current.environmentReasons());
                    reasons.add("cycad_callback_error");
                    store.replace(current, current.withEnvironmentReasons(reasons));
                });
                org.slf4j.LoggerFactory.getLogger(PlantCycadScheduler.class).error("Cycad pollination failed for " + id, failure);
            }
        });
    }
}
