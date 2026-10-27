package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Temporary cutting crown. Species prepares the half-height model; shared code owns timing and commits. */
public final class PlantCuttingBuds {
    private PlantCuttingBuds() {}
    @FunctionalInterface
    public interface Builder {
        /** Read-only preparation. Empty means obstructed; dependencies must include the rooted stem. */
        Optional<PlantStructurePlan> prepare(ServerLevel level, PlantIndividualState expected, PlantCuttingBud bud);
    }
    public record Profile(String stage, String role, Builder builder) {
        public Profile {
            if (stage.isBlank() || role.isBlank()) throw new IllegalArgumentException("Blank cutting role/stage");
            java.util.Objects.requireNonNull(builder);
        }
    }
    private static final Map<ResourceLocation, Profile> PROFILES = new ConcurrentHashMap<>();
    public static void register(ResourceLocation species, Profile profile) {
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        if (!definition.stages().containsKey(profile.stage())) throw new IllegalArgumentException("Unknown cutting stage");
        if (PROFILES.putIfAbsent(species, profile) != null) throw new IllegalArgumentException("Duplicate cutting profile");
    }

    /** Call after the rooted-texture stem was successfully placed and registered. Replays never reroll. */
    public static boolean establish(PlantIndividuals store, PlantIndividualState expected, BlockPos budPosition, RandomSource random) {
        java.util.Objects.requireNonNull(random);
        var profile = PROFILES.get(expected.species());
        if (store.isReadOnly() || store.isApplyingStructure() || profile == null || !profile.stage().equals(expected.stage()) || !eligible(expected)
                || !expected.equals(store.find(expected.id()).orElse(null)) || store.cuttingBud(expected.id()).isPresent()
                || !store.partsOf(expected.id()).containsKey(expected.base())
                || !budPosition.equals(expected.base().above()) || store.atPart(budPosition).isPresent()
                || store.atBase(budPosition).isPresent()) return false;
        return store.initializeCuttingBud(expected, PlantCuttingBud.waiting(expected.stage(), profile.role(), budPosition, random));
    }

    /** Dedicated destruction path: no items or grass stalks. Never route this temporary crown to withering. */
    public static PlantStructureCommitter.Result breakBud(ServerLevel level, PlantIndividualState expected,
            Map<BlockPos, BlockState> observed, RandomSource random) {
        java.util.Objects.requireNonNull(random);
        var store = PlantIndividuals.get(level);
        var bud = store.cuttingBud(expected.id()).orElse(null);
        if (bud == null || !bud.present() || !expected.equals(store.find(expected.id()).orElse(null))
                || !store.partRole(expected.id(), bud.position()).filter(bud.role()::equals).isPresent()
                || !observed.containsKey(bud.position())) return rejected();
        var plan = new PlantStructurePlan(expected, Map.of(bud.position(),
                PlantStructurePlan.Change.removeOwned(observed.get(bud.position()))), observed);
        var result = PlantStructureCommitter.apply(level, store, plan, expected);
        if (result.status() == PlantStructureCommitter.Status.COMMITTED
                && expected.equals(store.find(expected.id()).orElse(null))
                && store.partRole(expected.id(), bud.position()).isEmpty())
            store.replaceCuttingBud(expected.id(), bud,
                    PlantCuttingBud.waiting(bud.stage(), bud.role(), bud.position(), random));
        else if (result.status() == PlantStructureCommitter.Status.RECOVERY_REQUIRED
                && store.find(expected.id()).isPresent()
                && store.partRole(expected.id(), bud.position()).isEmpty())
            store.replaceCuttingBud(expected.id(), bud, new PlantCuttingBud(bud.stage(), bud.role(), bud.position(),
                    bud.targetTicks(), bud.targetTicks(), false, true));
        return result;
    }

    private static PlantStructureCommitter.Result rejected() {
        return new PlantStructureCommitter.Result(PlantStructureCommitter.Status.REJECTED, Optional.empty());
    }
    static boolean eligible(PlantIndividualState plant) {
        return plant.source().canFinishCurrentCycle() && !plant.pruned() && plant.environmentReasons().isEmpty();
    }

    static final class Scheduler {
        private final PlantGrowthScheduler queue = new PlantGrowthScheduler(32);
        PlantCuttingBud current(UUID id, PlantCuttingBud saved) {
            return saved == null || saved.present() || saved.failed() ? saved
                    : saved.elapsed(Math.min(saved.targetTicks(), queue.currentElapsed(id, saved.elapsedTicks())));
        }
        void cancel(UUID id) { queue.cancel(id); }
        void update(PlantIndividuals store, PlantIndividualState plant) {
            var bud = store.cuttingBud(plant.id()).orElse(null);
            cancel(plant.id());
            if (bud == null) return;
            store.captureCuttingBud(plant.id(), bud);
            if (!bud.stage().equals(plant.stage()) || PlantWithering.isWithering(plant)) {
                store.clearCuttingBud(plant.id()); return;
            }
            if (!bud.present()) store.pauseCuttingGrowth(plant.id());
            var profile = PROFILES.get(plant.species());
            if (bud.present() || bud.failed() || !eligible(plant) || profile == null
                    || !profile.stage().equals(bud.stage()) || !profile.role().equals(bud.role())) return;
            // Obstructed due crowns retry once per 1000 active ticks, never every tick.
            queue.updateSystem(plant.id(), plant.base(), bud.targetTicks() + (bud.ready() ? 1000 : 0), bud.elapsedTicks());
        }
        void tick(ServerLevel level, PlantIndividuals store) {
            if (store.isReadOnly() || store.isApplyingStructure()) return;
            queue.tickSystems(level, store::setDirty, id -> {
                var plant = store.find(id).orElse(null);
                var bud = store.cuttingBud(id).orElse(null);
                if (plant == null || bud == null || !eligible(plant) || !bud.ready()) { cancel(id); return; }
                var profile = PROFILES.get(plant.species());
                if (profile == null) { cancel(id); return; }
                try {
                    var proposal = profile.builder().prepare(level, plant, bud);
                    if (!plant.equals(store.find(id).orElse(null)) || !bud.equals(store.cuttingBud(id).orElse(null))) {
                        update(store, store.find(id).orElseThrow()); return;
                    }
                    if (proposal.isEmpty()) { update(store, plant); return; }
                    var plan = proposal.get();
                    var change = plan.changes().get(bud.position());
                    if (!plan.expectedIndividual().equals(plant) || plan.changes().size() != 1 || change == null
                            || !change.role().equals(bud.role()) || change.expected().equals(change.replacement())
                            || !plan.dependencies().containsKey(plant.base())
                            || !store.partsOf(id).containsKey(plant.base()) || store.atPart(bud.position()).isPresent()
                            || store.atBase(bud.position()).isPresent())
                        throw new IllegalArgumentException("Invalid cutting bud proposal");
                    var result = PlantStructureCommitter.apply(level, store, plan, plant);
                    if ((result.status() == PlantStructureCommitter.Status.COMMITTED
                            || result.status() == PlantStructureCommitter.Status.RECOVERY_REQUIRED)
                            && store.partRole(id, bud.position()).filter(bud.role()::equals).isPresent())
                        store.replaceCuttingBud(id, bud, bud.grown());
                    else if (result.status() == PlantStructureCommitter.Status.RECOVERY_REQUIRED)
                        store.replaceCuttingBud(id, bud, bud.fail());
                    else update(store, plant);
                } catch (RuntimeException error) {
                    store.replaceCuttingBud(id, bud, bud.fail());
                    org.slf4j.LoggerFactory.getLogger(PlantCuttingBuds.class).error("Cutting crown failed for " + id, error);
                }
            });
        }
    }
}
