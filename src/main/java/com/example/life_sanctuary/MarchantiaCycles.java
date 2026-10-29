package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Adult cycles reuse the saved active-chunk stage clock; no per-block ticker or world scan. */
public final class MarchantiaCycles {
    private MarchantiaCycles() {}
    public static final String QUOTA = "receptacles", PENDING = "capsule_pending";
    public static final int MIN_SPREAD = 28_800, MAX_SPREAD = 48_000, MIN_CAPSULE = 10_000, MAX_CAPSULE = 30_000;
    public enum Result { CHANGED, REJECTED, RECOVERY_REQUIRED }

    public static boolean healthy(PlantIndividualState plant) {
        return plant != null && plant.species().equals(MarchantiaRules.SPECIES)
                && plant.stage().equals(MarchantiaRules.ADULT)
                && !plant.pruned() && plant.environmentReasons().isEmpty();
    }
    public static PlantIndividualState quota(PlantIndividualState plant, int count) {
        if (count < 0 || count > 1) throw new IllegalArgumentException("One capsule has at most one harvest");
        var quotas = new HashMap<>(plant.harvests()); quotas.put(QUOTA, count);
        return new PlantIndividualState(plant.id(), plant.species(), plant.base(), plant.source(), plant.stage(),
                plant.population(), plant.canBePruned(), plant.pruned(), plant.targetTicks(), plant.elapsedTicks(),
                plant.environmentReasons(), quotas, plant.origin(), plant.reproduction(), plant.runnerBudget());
    }
    public static void schedule(ServerLevel level, PlantIndividualState expected, boolean capsule) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (!healthy(expected) || !expected.source().canExpand()) return;
        var budgets = new HashMap<>(expected.reproduction());
        budgets.put(PENDING, capsule ? 1 : 0);
        int minimum = capsule ? MIN_CAPSULE : MIN_SPREAD;
        int maximum = capsule ? MAX_CAPSULE : MAX_SPREAD;
        PlantIndividuals.get(level).replace(expected, expected.withReproduction(budgets)
                .withTimer(minimum + level.random.nextInt(maximum - minimum + 1), 0));
    }
    public static void ready(ServerLevel level, PlantIndividuals store, PlantIndividualState plant) {
        if (!plant.species().equals(MarchantiaRules.SPECIES) || !plant.source().canExpand() || store.isReadOnly()
                || store.isApplyingStructure() || !store.find(plant.id()).filter(plant::equals).isPresent()) return;
        BlockState state = level.getBlockState(plant.base());
        if (!state.is(LifeSanctuaryMod.MARCHANTIA.get()) || !owned(store, plant)
                || MarchantiaState.check(plant, state.getValue(MarchantiaBlock.REPRODUCTIVE), store.partsOf(plant.id())) != MarchantiaState.Problem.NONE) {
            fail(store, plant, "marchantia_state_error"); return;
        }
        if (!healthy(plant)) return;
        if (state.getValue(MarchantiaBlock.REPRODUCTIVE)) { store.replace(plant, plant.withTimer(0, 0)); return; }
        if (plant.reproduction().getOrDefault(PENDING, 0) == 1) {
            if (makeCapsule(level, plant) == Result.CHANGED)
                store.find(plant.id()).ifPresent(current -> store.replace(current, current.withTimer(0, 0)));
            return;
        }
        boolean expanded = trySpread(level, plant);
        // Child registration does not replace the parent snapshot, but may fail into recovery.
        store.find(plant.id()).filter(plant::equals).ifPresent(current -> schedule(level, current, !expanded));
    }
    private static boolean trySpread(ServerLevel level, PlantIndividualState plant) {
        var store = PlantIndividuals.get(level);
        if (store.populationCounts(plant.species(), plant.population()).total() >= MarchantiaRules.ARTIFICIAL_LIMIT) return false;
        var directions = new java.util.ArrayList<>(java.util.List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));
        for (int i = directions.size() - 1; i > 0; i--)
            java.util.Collections.swap(directions, i, level.random.nextInt(i + 1));
        for (var candidate : MarchantiaRules.spreadCandidates(plant.base(), directions)) {
            var result = MarchantiaRules.spread(level, plant, candidate);
            if (result.success()) return true;
            if (result.recoveryRequired()) {
                PlantLifecycle.setPauseReason(store, plant.id(), "marchantia_spread_error", true);
                return false;
            }
        }
        return false;
    }
    public static boolean owned(PlantIndividuals store, PlantIndividualState plant) {
        return !store.isReadOnly() && !store.isApplyingStructure()
                && store.find(plant.id()).filter(plant::equals).isPresent()
                && store.partsOf(plant.id()).equals(Map.of(plant.base(), "liverwort"));
    }

    /** One-block appearance and saved quota change together under the shared reentrancy lock. */
    public static Result makeCapsule(ServerLevel level, PlantIndividualState plant) {
        if (!available(level, plant.base())) return Result.REJECTED;
        var store = PlantIndividuals.get(level);
        if (!healthy(plant) || plant.source().isStatic() || !owned(store, plant)) return Result.REJECTED;
        var before = level.getBlockState(plant.base());
        if (!before.is(LifeSanctuaryMod.MARCHANTIA.get()) || before.getValue(MarchantiaBlock.REPRODUCTIVE)) return Result.REJECTED;
        if (MarchantiaState.check(plant, false, store.partsOf(plant.id())) != MarchantiaState.Problem.NONE)
            return fail(store, plant, "marchantia_state_error");
        var target = before.setValue(MarchantiaBlock.REPRODUCTIVE, true).setValue(MarchantiaBlock.FEMALE_COUNT, 1 + level.random.nextInt(2));
        var reserved = quota(plant, 1);
        if (!store.replace(plant, reserved)) return Result.REJECTED;
        boolean written = false;
        try {
            store.beginStructure(reserved);
            try {
                written = level.setBlock(plant.base(), target, quiet()) && level.getBlockState(plant.base()).equals(target);
            } finally { store.endStructure(); }
            if (!written) {
                if (level.getBlockState(plant.base()).equals(before) && store.replace(reserved, plant)) return Result.REJECTED;
                return fail(store, plant, "marchantia_capsule_error");
            }
            notifyWorld(level, plant.base());
            if (!level.getBlockState(plant.base()).equals(target)
                    || !store.find(plant.id()).filter(reserved::equals).isPresent()) return fail(store, plant, "marchantia_capsule_error");
            if (!store.replace(reserved, reserved.withTimer(0, 0))) return fail(store, plant, "marchantia_capsule_error");
            return Result.CHANGED;
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Marchantia capsule creation failed at " + plant.base(), failure);
            return fail(store, plant, "marchantia_capsule_error");
        }
    }

    /** false inventory exchange must leave inventory untouched; exceptions retain the zero reservation. */
    public static Result harvest(ServerLevel level, BlockPos pos, SporeHarvestAccess access) {
        if (!available(level, pos)) return Result.REJECTED;
        var store = PlantIndividuals.get(level);
        var plant = store.atBase(pos).orElse(null);
        if (plant == null || !owned(store, plant) || !PlantHarvestEligibility.canCollectSporeOrSeed(plant)
                || plant.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))) return Result.REJECTED;
        var before = level.getBlockState(pos);
        if (!before.is(LifeSanctuaryMod.MARCHANTIA.get()) || !before.getValue(MarchantiaBlock.REPRODUCTIVE)) return Result.REJECTED;
        if (MarchantiaState.check(plant, true, store.partsOf(plant.id())) != MarchantiaState.Problem.NONE)
            return fail(store, plant, "marchantia_state_error");
        var reserved = quota(plant, 0);
        if (!store.replace(plant, reserved)) return Result.REJECTED;
        boolean exchanged = false;
        boolean unchangedInventory = false;
        try {
            store.beginStructure(reserved);
            try {
                exchanged = access.exchangeSporeBag(MarchantiaRules.SPECIES.getPath(), pos);
                unchangedInventory = !exchanged;
                if (exchanged && (!level.getBlockState(pos).equals(before)
                        || !level.setBlock(pos, before.setValue(MarchantiaBlock.REPRODUCTIVE, false), quiet())
                        || !level.getBlockState(pos).equals(before.setValue(MarchantiaBlock.REPRODUCTIVE, false))))
                    throw new IllegalStateException("Marchantia changed during bag exchange");
            } finally { store.endStructure(); }
            if (!exchanged) {
                if (level.getBlockState(pos).equals(before) && store.replace(reserved, plant)) return Result.REJECTED;
                return fail(store, plant, "marchantia_collection_error");
            }
            notifyWorld(level, pos);
            if (!level.getBlockState(pos).equals(before.setValue(MarchantiaBlock.REPRODUCTIVE, false))
                    || !store.find(plant.id()).filter(reserved::equals).isPresent()) return fail(store, plant, "marchantia_collection_error");
            schedule(level, reserved, false);
            return Result.CHANGED;
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Marchantia spore exchange failed at " + pos + "; inventory unchanged=" + unchangedInventory, failure);
            return fail(store, plant, "marchantia_collection_error");
        }
    }
    private static int quiet() { return Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS; }
    private static boolean available(ServerLevel level, BlockPos pos) {
        return level.getServer().isSameThread() && !level.isOutsideBuildHeight(pos)
                && level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }
    private static void notifyWorld(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        state.updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
        level.updateNeighborsAt(pos, state.getBlock());
    }
    private static Result fail(PlantIndividuals store, PlantIndividualState plant, String reason) {
        // Static plants also need a diagnostic marker, despite having no running growth timer.
        if (!store.isReadOnly()) store.find(plant.id()).ifPresent(current -> {
            var reasons = new java.util.HashSet<>(current.environmentReasons()); reasons.add(reason);
            store.replace(current, current.withEnvironmentReasons(reasons));
        });
        return Result.RECOVERY_REQUIRED;
    }
}
