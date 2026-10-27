package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** 顶部只进不出的投入通道，虚拟槽映射培育名额，不将批次预览暴露为可搬走的物品。 */
public final class CultivationCabinetInputHandler implements IItemHandler {
    private final CultivationCabinetBlockEntity cabinet;

    public CultivationCabinetInputHandler(CultivationCabinetBlockEntity cabinet) {
        this.cabinet = java.util.Objects.requireNonNull(cabinet);
    }

    private Optional<CultivationCabinetAccess> access() {
        if (!(cabinet.getLevel() instanceof ServerLevel server) || !server.getServer().isSameThread()
                || cabinet.isRemoved() || !CultivationCabinetWorldConnection.isLoaded(server, cabinet.getBlockPos())
                || server.getBlockEntity(cabinet.getBlockPos()) != cabinet) return Optional.empty();
        return CultivationCabinetAccess.open(server, cabinet.getBlockPos());
    }

    @Override public int getSlots() { return access().map(value -> value.snapshot().capacity()).orElse(0); }
    @Override public ItemStack getStackInSlot(int slot) { return ItemStack.EMPTY; }
    @Override public int getSlotLimit(int slot) { return slot >= 0 && slot < getSlots() ? 1 : 0; }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }

    @Override public boolean isItemValid(int slot, ItemStack stack) {
        var current = access().orElse(null);
        return current != null && slot >= 0 && slot < current.snapshot().capacity()
                && current.snapshot().canInsertAt(slot)
                && CultivationRecipes.forSporeBag(stack).filter(CultivationProducts::isReady).isPresent();
    }

    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return stack;
        var current = access().orElse(null);
        if (current == null || slot < 0 || slot >= current.snapshot().capacity()
                || !current.snapshot().canInsertAt(slot)) return stack;
        var recipe = CultivationRecipes.forSporeBag(stack).filter(CultivationProducts::isReady).orElse(null);
        if (recipe == null) return stack;
        var remainder = stack.getCount() == 1 ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - 1);
        // 模拟到此结束，不分配批次 ID、不抽时长、不调用控制器，也不修改传入物品栈。
        if (simulate) return remainder;
        var expected = stack.copy();
        var result = current.start(slot, java.util.UUID.randomUUID(), recipe,
                species -> CultivationSporeBagInput.matchesSpecies(expected, species));
        // IItemHandler 以返回余量确认转移一袋，真实来源库存由调用方按余量更新。
        return result == CultivationController.Result.CHANGED || result == CultivationController.Result.RECOVERY_REQUIRED
                ? remainder : stack;
    }
}
