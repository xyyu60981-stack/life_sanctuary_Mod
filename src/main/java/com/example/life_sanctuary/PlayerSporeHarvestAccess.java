package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Player inventory adapter for spore harvesting.
 *
 * <p>The initial implementation intentionally checks only the two hand slots.
 * Portable collector contents can be added later without changing the plant
 * or quota code.</p>
 */
public final class PlayerSporeHarvestAccess implements SporeHarvestAccess {
    private final ServerPlayer player;

    public PlayerSporeHarvestAccess(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public boolean hasEmptySporeBag() {
        return isEmptySporeBag(player.getMainHandItem()) || isEmptySporeBag(player.getOffhandItem());
    }

    @Override
    public void consumeEmptySporeBag() {
        if (consumeFromHand(player.getMainHandItem())) return;
        consumeFromHand(player.getOffhandItem());
    }

    @Override
    public void giveSporeBag(String speciesId, BlockPos source) {
        ItemStack result = SporeBagItem.filled(speciesId);
        if (!player.getInventory().add(result)) player.drop(result, false);
    }

    private static boolean isEmptySporeBag(ItemStack stack) {
        return stack.is(LifeSanctuaryMod.EMPTY_SPORE_BAG.get())
                || (stack.is(LifeSanctuaryMod.SPORE_BAG.get()) && SporeBagItem.species(stack) == null);
    }

    private static boolean consumeFromHand(ItemStack stack) {
        if (!isEmptySporeBag(stack)) return false;
        stack.shrink(1);
        return true;
    }
}
