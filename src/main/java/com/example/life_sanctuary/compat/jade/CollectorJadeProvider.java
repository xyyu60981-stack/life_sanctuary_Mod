package com.example.life_sanctuary.compat.jade;

import com.example.life_sanctuary.*;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

enum CollectorJadeProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    INSTANCE;
    private static final String KEY = "LifeSanctuaryCollector";
    @Override public ResourceLocation getUid() { return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "collector_status"); }
    @Override public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel)
                || !(accessor.getBlockEntity() instanceof CollectorBlockEntity collector) || collector.isRemoved()) return;
        var summary = collector.summarizeContents();
        var data = new CompoundTag();
        data.putInt("Version", 1); data.putLong("Position", accessor.getPosition().asLong());
        data.putInt("Occupied", summary.occupiedSlots()); data.putInt("Capacity", summary.capacity());
        data.putInt("SporeBags", summary.emptySporeBags()); data.putInt("PollenBags", summary.emptyPollenBags());
        data.putString("Filter", collector.filter().mode().name());
        tag.put(KEY, data);
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        var root = accessor.getServerData();
        if (!root.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return;
        var data = root.getCompound(KEY);
        if (data.getInt("Version") != 1 || !data.contains("Position", net.minecraft.nbt.Tag.TAG_LONG)
                || data.getLong("Position") != accessor.getPosition().asLong()) return;
        int capacity = data.getInt("Capacity"), occupied = data.getInt("Occupied");
        if ((capacity != 27 && capacity != 54) || occupied < 0 || occupied > capacity) return;
        tooltip.add(Component.translatable("jade.life_sanctuary.collector.slots", occupied, capacity));
        String mode = data.getString("Filter").toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("off", "blacklist", "whitelist", "broken").contains(mode))
            tooltip.add(Component.translatable("jade.life_sanctuary.collector.filter." + mode));
        tooltip.add(Component.translatable("jade.life_sanctuary.collector.bags",
                Math.max(0, data.getInt("SporeBags")), Math.max(0, data.getInt("PollenBags"))).withStyle(ChatFormatting.GRAY));
    }
}
