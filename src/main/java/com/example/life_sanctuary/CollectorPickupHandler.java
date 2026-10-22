package com.example.life_sanctuary;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/** Collision-driven pickup only. No area searches, inventory recursion or ticking. */
public final class CollectorPickupHandler {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPickup(ItemEntityPickupEvent.Pre event) {
        var player = event.getPlayer();
        var entity = event.getItemEntity();
        if (player.level().isClientSide || player instanceof FakePlayer || !player.isAlive() || player.isSpectator()
                || event.canPickup().isFalse() || !Config.COLLECTOR_AUTO_PICKUP.get()) return;
        if (!event.canPickup().isTrue() && (entity.hasPickUpDelay()
                || (entity.getTarget() != null && !entity.getTarget().equals(player.getUUID())))) return;
        ItemStack incoming = entity.getItem();
        if (incoming.isEmpty() || !CollectorInventory.accepts(incoming)
                || ReproductiveBagKind.SPORE.isEmptyBag(incoming) || ReproductiveBagKind.POLLEN.isEmptyBag(incoming)) return;
        if (Config.COLLECTOR_PICKUP_BLACKLIST.get().contains(BuiltInRegistries.ITEM.getKey(incoming.getItem()).toString())) return;
        ItemStack box = player.getItemBySlot(EquipmentSlot.LEGS);
        if (CollectorInventory.capacity(box) == 0) return;

        ItemStack original = incoming.copy();
        int accepted = CollectorInventory.insert(box, incoming);
        if (accepted == 0) return;
        incoming.shrink(accepted); // Never replace the ItemEntity stack during this event.
        player.getInventory().setChanged();
        player.take(entity, accepted);
        player.awardStat(Stats.ITEM_PICKED_UP.get(original.getItem()), accepted);
        player.onItemPickup(entity);
        // Report the redirected portion. Vanilla handles any remaining portion separately.
        EventHooks.fireItemPickupPost(entity, player, original);
        if (incoming.isEmpty()) {
            entity.discard();
            event.setCanPickup(TriState.FALSE); // Fully handled, do not enter vanilla pickup a second time.
        }
    }
}
