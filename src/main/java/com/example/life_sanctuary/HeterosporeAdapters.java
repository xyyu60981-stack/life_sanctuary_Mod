package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Real species adapters must commit their entire world/state transition before reporting success. */
public final class HeterosporeAdapters {
    private HeterosporeAdapters() {}
    public enum Result { APPLIED, BLOCKED, RECOVERY_REQUIRED }
    public interface WorldChange {
        Result grow(ServerLevel level, PlantIndividualState expected);
        Result disappear(ServerLevel level, PlantIndividualState expected);
    }
    public record Profile(String gametophyteStage, WorldChange change) {
        public Profile { if (gametophyteStage == null || gametophyteStage.isBlank()) throw new IllegalArgumentException("Missing gametophyte stage"); java.util.Objects.requireNonNull(change); }
    }
    public record Seedling(PlantStageStructure structure, Map<BlockPos, BlockState> observed, PlantStageResources resources) {
        public Seedling {
            java.util.Objects.requireNonNull(structure); java.util.Objects.requireNonNull(resources);
            var snapshot = new java.util.LinkedHashMap<BlockPos, BlockState>();
            observed.forEach((pos, state) -> snapshot.put(pos.immutable(), java.util.Objects.requireNonNull(state)));
            observed = Map.copyOf(snapshot);
        }
    }
    @FunctionalInterface public interface SeedlingPlan {
        /** Check real species support/shape without loading chunks; empty means temporarily blocked. */
        Optional<Seedling> prepare(ServerLevel level, PlantIndividualState expected);
    }
    /** Standard adapter: shared atomic growth plus exact water-preserving, drop-free removal. */
    public static Profile structural(String gametophyteStage, SeedlingPlan planner) {
        java.util.Objects.requireNonNull(planner);
        return new Profile(gametophyteStage, new WorldChange() {
            @Override public Result grow(ServerLevel level, PlantIndividualState expected) {
                if (!HeterosporeWorldData.get(level).authorizes(level, expected, HeterosporeCycle.Action.SEEDLING)) return Result.BLOCKED;
                return planner.prepare(level, expected).map(plan -> HeterosporeTransitions.grow(level, expected,
                        plan.structure(), plan.observed(), plan.resources())).orElse(Result.BLOCKED);
            }
            @Override public Result disappear(ServerLevel level, PlantIndividualState expected) {
                return HeterosporeTransitions.disappear(level, expected);
            }
        });
    }
    private static final Map<ResourceLocation, Profile> PROFILES = new ConcurrentHashMap<>();
    public static void register(ResourceLocation species, Profile profile) {
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        if (!PlantSporeTraits.find(species).differentiatedSpores() || !definition.stages().containsKey(profile.gametophyteStage())
                || definition.stages().get(profile.gametophyteStage()).transition().isEmpty()) throw new IllegalArgumentException("Missing heterospore stage transition");
        if (PROFILES.putIfAbsent(species, profile) != null) throw new IllegalArgumentException("Duplicate heterospore adapter");
    }
    static Profile find(ResourceLocation species) { return PROFILES.get(species); }
}
