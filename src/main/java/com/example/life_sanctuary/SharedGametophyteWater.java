package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Species/stage water rules independent of the common visual block. No guessed default requirement. */
public final class SharedGametophyteWater {
    private SharedGametophyteWater() {}
    private record Key(ResourceLocation species, String stage) {}
    @FunctionalInterface public interface Decline {
        /** Use a complete structural transaction; true only after successful removal or dead conversion. */
        boolean finish(ServerLevel level, PlantIndividualState expected);
    }
    private record Rule(WaterDependentPlant.Requirement requirement, Decline decline) {}
    private static final Map<Key, Rule> RULES = new ConcurrentHashMap<>();
    /** Standard gametophyte decline: no drops, exact original water state, retire only after full commit. */
    public static void registerDisappearing(ResourceLocation species, String stage, WaterDependentPlant.Requirement requirement) {
        register(species, stage, requirement, SharedGametophyteWater::disappear);
    }
    private static boolean disappear(ServerLevel level, PlantIndividualState expected) {
        var store = PlantIndividuals.get(level);
        var parts = store.partsOf(expected.id());
        if (parts.isEmpty() || !parts.containsKey(expected.base())) return false;
        // Confirm every chunk before capturing any world block, without triggering chunk loads.
        for (var pos : parts.keySet())
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        var observed = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        parts.keySet().forEach(pos -> observed.put(pos, level.getBlockState(pos)));
        var result = PlantStructureRemoval.removeIndividual(level, expected, observed);
        return switch (result.commit().status()) {
            case COMMITTED -> true;
            case REJECTED -> false;
            case ROLLED_BACK, RECOVERY_REQUIRED -> throw new IllegalStateException("Water decline requires recovery: " + expected.id());
        };
    }
    public static void register(ResourceLocation species, String stage, WaterDependentPlant.Requirement requirement, Decline decline) {
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        if (!definition.stages().containsKey(stage) || definition.stages().get(stage).fullyAdult())
            throw new IllegalArgumentException("Water rule requires a non-adult stage");
        if (species.equals(PlantSpeciesRegistry.HORSETAIL.id()))
            throw new IllegalArgumentException("Horsetail never requires nearby water");
        var rule = new Rule(java.util.Objects.requireNonNull(requirement), java.util.Objects.requireNonNull(decline));
        if (RULES.putIfAbsent(new Key(species, stage), rule) != null) throw new IllegalArgumentException("Duplicate water rule");
    }
    private static PlantIndividualState plant(ServerLevel level, BlockPos pos) {
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly()) return null;
        var plant = store.atBase(pos).orElse(null);
        return plant == null || plant.source().isStatic()
                || plant.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))
                || GametophyteIdentity.at(level, pos).isEmpty() ? null : plant;
    }
    public static Optional<WaterDependentPlant.Requirement> requirement(ServerLevel level, BlockPos pos) {
        var plant = plant(level, pos);
        var rule = plant == null ? null : RULES.get(new Key(plant.species(), plant.stage()));
        return rule == null ? Optional.empty() : Optional.of(rule.requirement());
    }
    public static void changed(ServerLevel level, BlockPos pos, boolean dry) {
        var plant = plant(level, pos);
        if (plant != null) PlantLifecycle.setPauseReason(PlantIndividuals.get(level), plant.id(), "lack_water", dry);
    }
    public static boolean finish(ServerLevel level, BlockPos pos) {
        var plant = plant(level, pos);
        var rule = plant == null ? null : RULES.get(new Key(plant.species(), plant.stage()));
        if (rule == null) return false;
        try {
            if (!rule.decline().finish(level, plant)) return false;
            if (PlantIndividuals.get(level).find(plant.id()).isEmpty()) return true;
            LifeSanctuaryMod.LOGGER.error("Gametophyte water decline reported success without retiring {}", plant.id());
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Gametophyte water decline failed for " + plant.id(), failure);
        }
        HeterosporeWorldData.get(level).freezeForRecovery(plant);
        PlantLifecycle.setPauseReason(PlantIndividuals.get(level), plant.id(), "gametophyte_water_error", true);
        return false;
    }
}
