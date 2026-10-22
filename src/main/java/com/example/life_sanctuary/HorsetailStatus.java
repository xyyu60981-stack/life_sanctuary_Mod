package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

/** Immutable server snapshot for inspection tools. Unknown data is never reported as healthy. */
public record HorsetailStatus(Optional<PlantStage> stage, PlantSource source,
        Optional<PlantPopulationTag> population, boolean canBePruned, boolean pruned,
        int remainingSporeHarvests, long maturityTicks, Optional<PlantClimate> climate) {

    public enum GrowthStatus { STATIC, UNKNOWN, PAUSED_COLD, CLIMATE_ALLOWS_GROWTH }

    /** Only describes climate permission, not whether maturity or population limits permit growth. */
    public GrowthStatus growthStatus() {
        if (source.isStatic()) return GrowthStatus.STATIC;
        if (climate.isEmpty()) return GrowthStatus.UNKNOWN;
        return climate.get().cold() ? GrowthStatus.PAUSED_COLD : GrowthStatus.CLIMATE_ALLOWS_GROWTH;
    }

    /** A future Jade server provider can send this payload without reading biomes or searching water. */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        stage.ifPresent(value -> tag.putString("Stage", value.name()));
        tag.putString("Source", source.name());
        population.ifPresent(value -> tag.putString("Population", value.serializedName()));
        tag.putBoolean("CanBePruned", canBePruned);
        tag.putBoolean("Pruned", pruned);
        tag.putInt("RemainingSporeHarvests", remainingSporeHarvests);
        // Stored timer value only: lifecycle countdown integration is a separate task.
        tag.putLong("MaturityTicks", maturityTicks);
        tag.putString("GrowthStatus", growthStatus().name());
        tag.putBoolean("ClimateKnown", climate.isPresent());
        climate.ifPresent(value -> {
            tag.putBoolean("Dry", value.dry());
            tag.putBoolean("Cold", value.cold());
            tag.putBoolean("Hot", value.hot());
        });
        return tag;
    }
}
