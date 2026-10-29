package com.example.life_sanctuary.compat.jade;

import com.example.life_sanctuary.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Read cached identity and timers only. Looking at moss must not scan biomes or run growth. */
enum LuminousMossJadeProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    INSTANCE;
    private static final String KEY = "LifeSanctuaryLuminousMoss";
    @Override public ResourceLocation getUid() { return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "luminous_moss_status"); }
    @Override public void appendServerData(CompoundTag root, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level) || !(accessor.getBlock() instanceof LuminousMossBlock)) return;
        var data = new CompoundTag();
        data.putInt("Version", 1); data.putLong("Position", accessor.getPosition().asLong());
        var store = PlantIndividuals.get(level);
        data.putBoolean("Recovery", store.isReadOnly());
        var plant = store.atBase(accessor.getPosition()).filter(p -> p.species().equals(LuminousMossRules.SPECIES)).orElse(null);
        int age = accessor.getBlockState().getValue(LuminousMossBlock.AGE);
        boolean known = plant != null;
        data.putBoolean("Known", known); data.putInt("Age", age);
        if (known) {
            var problem = LuminousMossState.check(plant, age, store.partsOf(plant.id()));
            if (problem == LuminousMossState.Problem.ENVIRONMENT_UNKNOWN) data.putBoolean("Known", false);
            data.putBoolean("Static", plant.source().isStatic());
            data.putBoolean("Cold", plant.environmentReasons().contains("cold"));
            data.putBoolean("Dry", plant.reproduction().getOrDefault(LuminousMossCycles.DRY, 0) != 0);
            data.putBoolean("Recovery", store.isReadOnly() || plant.environmentReasons().stream().anyMatch(r -> r.endsWith("_error"))
                    || problem != LuminousMossState.Problem.NONE && problem != LuminousMossState.Problem.ENVIRONMENT_UNKNOWN);
            data.putInt("Quota", age == 2 ? Math.min(1, plant.harvests().getOrDefault(LuminousMossCycles.QUOTA, 0)) : 0);
        }
        root.put(KEY, data);
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        var root = accessor.getServerData();
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) return;
        var data = root.getCompound(KEY);
        if (data.getInt("Version") != 1 || !data.contains("Position", Tag.TAG_LONG)
                || data.getLong("Position") != accessor.getPosition().asLong()) return;
        int age = data.getInt("Age"); if (age < 0 || age > 2) return;
        tooltip.add(Component.translatable("jade.life_sanctuary.moss.stage." + age));
        if (data.getBoolean("Recovery")) {
            tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.recovery_required")); return;
        }
        if (!data.getBoolean("Known")) { tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.unknown")); return; }
        if (data.getBoolean("Static")) tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.static"));
        else {
            if (data.getBoolean("Cold")) tooltip.add(Component.translatable("jade.life_sanctuary.moss.cold"));
            if (data.getBoolean("Dry")) tooltip.add(Component.translatable("jade.life_sanctuary.moss.dry"));
        }
        if (age == 2) tooltip.add(Component.translatable("jade.life_sanctuary.moss.quota", Math.max(0, Math.min(1, data.getInt("Quota")))));
    }
}
