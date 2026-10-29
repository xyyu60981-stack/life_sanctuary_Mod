package com.example.life_sanctuary;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** On-demand server snapshot for inspection tools; never initializes climate or advances biology. */
public record GametophyteStatus(GametophyteIdentity identity, PlantSource source, String stage,
        Growth growth, Set<String> reasons, Optional<HeterosporeCycle> cycle,
        Optional<PlantWaterMonitoring.Status> water) {
    public enum Growth { STATIC, UNKNOWN, ACTIVE, PAUSED, RECOVERY_REQUIRED }
    public GametophyteStatus {
        java.util.Objects.requireNonNull(identity);
        java.util.Objects.requireNonNull(source);
        java.util.Objects.requireNonNull(stage);
        java.util.Objects.requireNonNull(growth);
        reasons = Set.copyOf(reasons);
        java.util.Objects.requireNonNull(cycle);
        java.util.Objects.requireNonNull(water);
    }

    public static Optional<GametophyteStatus> at(ServerLevel level, BlockPos pos) {
        var identity = GametophyteIdentity.at(level, pos).orElse(null);
        if (identity == null) return Optional.empty();
        var store = PlantIndividuals.get(level);
        var plant = store.atPart(pos).orElse(null);
        if (plant == null) {
            var data = HorsetailPopulations.get(level);
            if (!identity.species().equals(PlantSpeciesRegistry.HORSETAIL.id()) || data.member(pos) == null)
                return Optional.empty();
            var legacy = data.statusAt(pos);
            Growth growth = switch (legacy.growthStatus()) {
                case STATIC -> Growth.STATIC;
                case UNKNOWN -> Growth.UNKNOWN;
                case PAUSED_COLD -> Growth.PAUSED;
                case CLIMATE_ALLOWS_GROWTH -> Growth.ACTIVE;
            };
            return Optional.of(new GametophyteStatus(identity, legacy.source(),
                    legacy.stage().map(Enum::name).orElse("unknown"), growth,
                    growth == Growth.PAUSED ? Set.of("cold") : Set.of(), Optional.empty(), Optional.empty()));
        }
        var biology = HeterosporeWorldData.get(level);
        var cycle = biology.cycle(plant.id());
        boolean fault = plant.environmentReasons().stream().anyMatch(reason -> reason.endsWith("_error"))
                || SharedGametophyteWater.monitoringUnavailable(level, plant)
                || cycle.isPresent() && biology.frozen(plant.id());
        Growth growth = fault ? Growth.RECOVERY_REQUIRED : plant.source().isStatic() ? Growth.STATIC
                : plant.pruned() || !plant.environmentReasons().isEmpty() ? Growth.PAUSED : Growth.ACTIVE;
        var water = SharedGametophyteWater.requirement(level, plant.base()).isPresent()
                ? PlantWaterMonitoring.get(level).inspect(plant.base())
                : Optional.<PlantWaterMonitoring.Status>empty();
        if (growth == Growth.ACTIVE && SharedGametophyteWater.requiresWater(plant) && water.isEmpty()) growth = Growth.UNKNOWN;
        return Optional.of(new GametophyteStatus(identity, plant.source(), plant.stage(), growth,
                plant.environmentReasons(), cycle, water));
    }

    /** Provider payload; the client must display it, not use it as placement or growth authority. */
    public CompoundTag toTag() {
        var tag = new CompoundTag();
        tag.putInt("Version", 1);
        tag.putString("Species", identity.species().toString());
        tag.putString("SporeForm", identity.form().name());
        tag.putString("Appearance", identity.appearance().name());
        tag.putString("Source", source.name());
        tag.putString("Stage", stage);
        tag.putString("Growth", growth.name());
        var entries = new net.minecraft.nbt.ListTag();
        reasons.stream().sorted().forEach(reason -> entries.add(net.minecraft.nbt.StringTag.valueOf(reason)));
        tag.put("Reasons", entries);
        tag.putBoolean("WaterKnown", water.isPresent());
        water.ifPresent(value -> {
            var moisture = new CompoundTag();
            moisture.putBoolean("LacksWater", value.lacksWater());
            moisture.putLong("RemainingDeclineTicks", value.remainingTicks());
            tag.put("Water", moisture);
        });
        cycle.ifPresent(value -> {
            var timing = new CompoundTag();
            timing.putBoolean("Mature", value.mature());
            timing.putInt("Attempts", value.attempts());
            timing.putString("Outcome", value.outcome().name());
            timing.putLong("RemainingEventTicks", value.remaining());
            timing.putString("DueAction", value.action().name());
            tag.put("Cycle", timing);
        });
        return tag;
    }
}
