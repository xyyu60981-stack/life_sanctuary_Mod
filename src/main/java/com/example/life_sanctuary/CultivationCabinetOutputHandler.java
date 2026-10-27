package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** 下方只出不进。每个培育名额提供四个固定实物槽，以支持不可堆叠产物。 */
public final class CultivationCabinetOutputHandler implements IItemHandler {
    private final CultivationCabinetBlockEntity cabinet;
    public CultivationCabinetOutputHandler(CultivationCabinetBlockEntity cabinet) { this.cabinet = java.util.Objects.requireNonNull(cabinet); }

    private Optional<CultivationCabinetAccess> access() {
        if (!(cabinet.getLevel() instanceof ServerLevel server) || !server.getServer().isSameThread()
                || cabinet.isRemoved() || !CultivationCabinetWorldConnection.isLoaded(server, cabinet.getBlockPos())
                || server.getBlockEntity(cabinet.getBlockPos()) != cabinet) return Optional.empty();
        return CultivationCabinetAccess.open(server, cabinet.getBlockPos());
    }
    @Override public int getSlots() { return access().map(value -> value.snapshot().capacity() * CultivationOutputBatch.STACK_SLOTS).orElse(0); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
    @Override public int getSlotLimit(int slot) { return slot >= 0 && slot < getSlots() ? CultivationRules.OUTPUT_PER_BAG : 0; }
    @Override public ItemStack getStackInSlot(int slot) { return extractItem(slot, Integer.MAX_VALUE, true); }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        var current = access().orElse(null);
        if (current == null || amount <= 0 || slot < 0
                || slot >= current.snapshot().capacity() * CultivationOutputBatch.STACK_SLOTS) return ItemStack.EMPTY;
        return current.extractAutomatedStack(slot / CultivationOutputBatch.STACK_SLOTS,
                slot % CultivationOutputBatch.STACK_SLOTS, amount, simulate);
    }
}
