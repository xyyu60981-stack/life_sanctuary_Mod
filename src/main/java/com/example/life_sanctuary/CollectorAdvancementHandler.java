package com.example.life_sanctuary;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;

/** Uses vanilla equipment detection, including equipped items restored when joining a world. */
public final class CollectorAdvancementHandler {
    private static final ResourceLocation PLANT_HUNTER = ResourceLocation.fromNamespaceAndPath(
            LifeSanctuaryMod.MODID, "plant_hunter");

    @SubscribeEvent
    public void onEquipmentChanged(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.LEGS || !(event.getEntity() instanceof ServerPlayer player)
                || player instanceof FakePlayer || CollectorInventory.capacity(event.getTo()) == 0) return;
        // Contents changing also counts as equipment changing. Avoid advancement work for those updates.
        if (CollectorInventory.capacity(event.getFrom()) > 0
                && event.getFrom().getItem() == event.getTo().getItem()) return;
        var server = player.getServer();
        if (server == null) return;
        var advancement = server.getAdvancements().get(PLANT_HUNTER);
        // Datapacks may remove/replace advancements; do not make equipment depend on their presence.
        if (advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone())
            player.getAdvancements().award(advancement, "equipped_collector");
    }
}
