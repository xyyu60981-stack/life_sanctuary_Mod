package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Completed cultivation stage only; unfinished progress and random timers are never copied. */
public record CultivatedStageData(ResourceLocation species, String phase) {
    private static final String KEY = "LifeSanctuaryCultivatedStage";
    public CultivatedStageData {
        java.util.Objects.requireNonNull(species);
        if (phase == null || phase.isBlank() || phase.length() > 128) throw new IllegalArgumentException("Invalid completed phase");
    }
    public void write(ItemStack stack) {
        var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var data = new net.minecraft.nbt.CompoundTag();
        data.putInt("Version", 1); data.putString("Species", species.toString()); data.putString("Phase", phase);
        root.put(KEY, data); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }
    public static Optional<CultivatedStageData> read(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        var root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) return Optional.empty();
        var data = root.getCompound(KEY);
        if (!data.contains("Version", Tag.TAG_INT) || data.getInt("Version") != 1
                || !data.contains("Species", Tag.TAG_STRING) || !data.contains("Phase", Tag.TAG_STRING)) return Optional.empty();
        var species = ResourceLocation.tryParse(data.getString("Species"));
        if (species == null || data.getString("Phase").isBlank() || data.getString("Phase").length() > 128) return Optional.empty();
        return Optional.of(new CultivatedStageData(species, data.getString("Phase")));
    }
}
