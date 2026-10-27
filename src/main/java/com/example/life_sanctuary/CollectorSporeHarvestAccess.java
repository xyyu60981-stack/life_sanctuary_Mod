package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Real and simulated players use the same hand/equipment boundary. */
public final class CollectorSporeHarvestAccess implements SporeHarvestAccess, PollenHarvestAccess {
    private final ServerPlayer player;
    private final InteractionHand hand;
    public CollectorSporeHarvestAccess(ServerPlayer player, InteractionHand hand) {
        this.player = player;
        this.hand = hand;
    }

    public static boolean canInteract(Player player, InteractionHand hand) {
        return CollectorInventory.capacity(player.getItemInHand(hand)) > 0
                || CollectorInventory.capacity(player.getItemBySlot(EquipmentSlot.LEGS)) > 0
                || (player instanceof FakePlayer && isEmptyBag(player.getItemInHand(hand)));
    }

    private static boolean isEmptyBag(ItemStack stack) {
        return ReproductiveBagKind.SPORE.isEmptyBag(stack);
    }

    /** Create owns filter selection and transport; only exchange the bag actually in its hand. */
    private boolean exchangeHeldBag(ReproductiveBagKind kind, ItemStack result, boolean simulate) {
        ItemStack held = player.getItemInHand(hand);
        if (!(player instanceof FakePlayer) || !kind.isEmptyBag(held) || !kind.isFilledBag(result)) return false;
        var inventory = player.getInventory().items;
        for (int i = 0; i < inventory.size(); i++) {
            // Create retains a matching item in the selected slot, but exports other slots.
            // Even the last empty bag must yield into a non-selected slot, otherwise an
            // empty/broad filter can retain the filled bag and use it on the next target.
            if (i == player.getInventory().selected) continue;
            ItemStack target = inventory.get(i);
            if (target.isEmpty() || (ItemStack.isSameItemSameComponents(target, result)
                    && target.getCount() < target.getMaxStackSize())) {
                if (!simulate) {
                    if (target.isEmpty()) inventory.set(i, result);
                    else target.grow(1);
                    held.shrink(1);
                    if (held.isEmpty()) player.setItemInHand(hand, ItemStack.EMPTY);
                    player.getInventory().setChanged();
                }
                return true;
            }
        }
        return false; // Never consume a bag/quota or scatter output when the fake inventory is full.
    }

    @Override
    public boolean exchangeSporeBag(String speciesId, BlockPos source) {
        return exchangeResult(SporeBagItem.filled(speciesId));
    }
    @Override public boolean exchangeSporeBag(String speciesId, SporeBagContents.Form form, BlockPos source) {
        return exchangeResult(SporeBagItem.filled(speciesId, form));
    }
    private boolean exchangeResult(ItemStack result) {
        if (player instanceof FakePlayer && isEmptyBag(player.getItemInHand(hand)))
            return exchangeHeldBag(ReproductiveBagKind.SPORE, result, false);
        if (CollectorInventory.exchangeSporeBag(player.getItemInHand(hand), result, false)) return true;
        return CollectorInventory.exchangeSporeBag(player.getItemBySlot(EquipmentSlot.LEGS), result, false);
    }

    @Override public boolean hasEmptySporeBag() {
        ItemStack result = SporeBagItem.filled("equisetum_arvense");
        if (player instanceof FakePlayer && isEmptyBag(player.getItemInHand(hand)))
            return exchangeHeldBag(ReproductiveBagKind.SPORE, result, true);
        return CollectorInventory.hasEmptyBag(player.getItemInHand(hand), ReproductiveBagKind.SPORE)
                || CollectorInventory.hasEmptyBag(player.getItemBySlot(EquipmentSlot.LEGS), ReproductiveBagKind.SPORE);
    }
    @Override public void consumeEmptySporeBag() { throw new UnsupportedOperationException("Use atomic exchangeSporeBag"); }
    @Override public boolean exchangePollenBag(String speciesId, BlockPos source) {
        if (speciesId == null || speciesId.isBlank()) return false;
        ItemStack result = PollenBagItem.filled(speciesId);
        if (player instanceof FakePlayer && ReproductiveBagKind.POLLEN.isEmptyBag(player.getItemInHand(hand)))
            return exchangeHeldBag(ReproductiveBagKind.POLLEN, result, false);
        if (CollectorInventory.exchangePollenBag(player.getItemInHand(hand), result, false)) return true;
        return CollectorInventory.exchangePollenBag(player.getItemBySlot(EquipmentSlot.LEGS), result, false);
    }
    @Override public void giveSporeBag(String speciesId, BlockPos source) { throw new UnsupportedOperationException("Use atomic exchangeSporeBag"); }
}
