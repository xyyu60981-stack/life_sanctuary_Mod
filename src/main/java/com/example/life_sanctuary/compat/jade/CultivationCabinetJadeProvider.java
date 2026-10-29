package com.example.life_sanctuary.compat.jade;

import com.example.life_sanctuary.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** Bounded two/four-slot read-only summary. Never opens a menu or initializes a controller. */
enum CultivationCabinetJadeProvider implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    INSTANCE;
    private static final String KEY = "LifeSanctuaryCultivation";
    @Override public ResourceLocation getUid() { return ResourceLocation.fromNamespaceAndPath("life_sanctuary", "cultivation_status"); }
    @Override public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level)
                || !(accessor.getBlockEntity() instanceof CultivationCabinetBlockEntity own)) return;
        var payload = new CompoundTag();
        payload.putInt("Version", 1);
        payload.putLong("Position", accessor.getPosition().asLong());
        var partner = CultivationCabinetWorldConnection.partner(level, accessor.getPosition());
        boolean paired = accessor.getBlockState().getValue(CultivationCabinetBlock.PART) != CultivationCabinetPairing.Part.SINGLE;
        if (paired && partner.isEmpty()) {
            payload.putBoolean("Unavailable", true);
            tag.put(KEY, payload); return;
        }
        boolean powered = own.cachedPower().orElse(false) || partner.map(other -> other.cachedPower().orElse(false)).orElse(false);
        boolean powerKnown = powered || own.cachedPower().isPresent()
                && (partner.isEmpty() || partner.orElseThrow().cachedPower().isPresent());
        var halves = new java.util.ArrayList<CultivationCabinetBlockEntity>();
        halves.add(own); partner.ifPresent(halves::add);
        int capacity = 0, occupied = 0, ready = 0, paused = 0, missing = 0, failed = 0;
        for (var half : halves) {
            var snapshot = half.status(powered);
            if (snapshot.readOnly() || snapshot.applying()) {
                payload.putBoolean("Unavailable", true); tag.put(KEY, payload); return;
            }
            capacity += snapshot.capacity();
            for (var slot : snapshot.slots()) {
                if (slot.state() != CultivationStatus.State.EMPTY) occupied++;
                switch (slot.state()) {
                    case FINAL_READY -> ready++;
                    case POWER_PAUSED -> paused++;
                    case MISSING_OUTPUT -> missing++;
                    case RECOVERY_REQUIRED, DATA_UNAVAILABLE -> failed++;
                    default -> { }
                }
            }
        }
        payload.putInt("Capacity", capacity); payload.putInt("Occupied", occupied); payload.putInt("Ready", ready);
        payload.putInt("Paused", powerKnown ? paused : 0); payload.putInt("Missing", missing); payload.putInt("Failed", failed);
        payload.putBoolean("PowerKnown", powerKnown);
        tag.put(KEY, payload);
    }
    @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        var root = accessor.getServerData();
        if (!root.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND)) return;
        var data = root.getCompound(KEY);
        if (data.getInt("Version") != 1 || !data.contains("Position", net.minecraft.nbt.Tag.TAG_LONG)
                || data.getLong("Position") != accessor.getPosition().asLong()) return;
        if (data.getBoolean("Unavailable")) {
            tooltip.add(Component.translatable("jade.life_sanctuary.cultivation.unavailable")); return;
        }
        int capacity = data.getInt("Capacity"), occupied = data.getInt("Occupied");
        if ((capacity != 2 && capacity != 4) || occupied < 0 || occupied > capacity) return;
        tooltip.add(Component.translatable("jade.life_sanctuary.cultivation.slots", occupied, capacity));
        tooltip.add(Component.translatable("jade.life_sanctuary.cultivation.ready", Math.max(0, Math.min(capacity, data.getInt("Ready")))));
        for (var field : java.util.List.of("Failed", "Missing", "Paused"))
            if (data.getInt(field) > 0) tooltip.add(Component.translatable("jade.life_sanctuary.cultivation." + field.toLowerCase(java.util.Locale.ROOT)));
        if (occupied > data.getInt("Ready") && !data.getBoolean("PowerKnown"))
            tooltip.add(Component.translatable("jade.life_sanctuary.cultivation.power_unknown"));
    }
}
