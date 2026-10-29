package com.example.life_sanctuary.compat.jade;

import com.example.life_sanctuary.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Reads saved state only: inspection never refreshes climate, repairs quotas or advances growth. */
enum StarMossJadeProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    INSTANCE;
    private static final String KEY = "LifeSanctuaryStarMoss";
    @Override public ResourceLocation getUid() {
        return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "star_moss_status");
    }
    @Override public void appendServerData(CompoundTag root, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level) || !(accessor.getBlock() instanceof StarMossBlock)) return;
        var pos = accessor.getPosition();
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        var state = accessor.getBlockState();
        boolean reproductive = state.getValue(StarMossBlock.REPRODUCTIVE);
        var data = new CompoundTag();
        data.putInt("Version", 1);
        data.putLong("Position", pos.asLong());
        data.putBoolean("Reproductive", reproductive);
        var store = PlantIndividuals.get(level);
        var plant = store.atBase(pos).filter(p -> p.species().equals(StarMossRules.SPECIES)).orElse(null);
        data.putBoolean("Known", plant != null);
        data.putBoolean("Recovery", store.isReadOnly());
        if (plant != null) {
            int quota = plant.harvests().getOrDefault(StarMossCycles.QUOTA, -1);
            int pending = plant.reproduction().getOrDefault(StarMossCycles.PENDING, 0);
            var problem = StarMossState.check(plant, reproductive, store.partsOf(plant.id()));
            data.putBoolean("Recovery", store.isReadOnly()
                    || problem != StarMossState.Problem.NONE
                    || plant.environmentReasons().stream().anyMatch(r -> r.endsWith("_error")));
            data.putBoolean("Known", true);
            data.putBoolean("Static", plant.source().isStatic());
            data.putBoolean("Paused", plant.pruned() || !plant.environmentReasons().isEmpty());
            data.putBoolean("WaitingReproduction", pending == 1);
            data.putInt("Quota", quota == 1 ? 1 : 0);
        }
        root.put(KEY, data);
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        var root = accessor.getServerData();
        if (!root.contains(KEY, Tag.TAG_COMPOUND)) return;
        var data = root.getCompound(KEY);
        if (data.getInt("Version") != 1 || !data.contains("Position", Tag.TAG_LONG)
                || data.getLong("Position") != accessor.getPosition().asLong()
                || !(accessor.getBlock() instanceof StarMossBlock)
                || data.getBoolean("Reproductive") != accessor.getBlockState().getValue(StarMossBlock.REPRODUCTIVE)) return;
        tooltip.add(Component.translatable("jade.life_sanctuary.star_moss."
                + (data.getBoolean("Reproductive") ? "reproductive" : "vegetative")));
        if (data.getBoolean("Recovery")) {
            tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.recovery_required")); return;
        }
        if (!data.getBoolean("Known")) {
            tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.unknown")); return;
        }
        if (data.getBoolean("Static")) tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.static"));
        else if (data.getBoolean("Paused")) tooltip.add(Component.translatable("jade.life_sanctuary.gametophyte.paused"));
        else if (!data.getBoolean("Reproductive")) tooltip.add(Component.translatable("jade.life_sanctuary.star_moss."
                + (data.getBoolean("WaitingReproduction") ? "waiting_reproduction" : "spreading")));
        if (data.getBoolean("Reproductive")) tooltip.add(Component.translatable("jade.life_sanctuary.star_moss.quota", data.getInt("Quota")));
    }
}
