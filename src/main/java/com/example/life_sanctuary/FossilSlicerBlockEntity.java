package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Two real slots. Manual work is a transient menu lease, never a block entity ticker. */
public final class FossilSlicerBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    private NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
    private FossilSlicerMenu operator;
    private long workRevision;
    long workRevision() { return workRevision; }
    public net.neoforged.neoforge.items.IItemHandler itemHandler(Direction side) {
        if (side != null) return new net.neoforged.neoforge.items.wrapper.SidedInvWrapper(this, side);
        return new net.neoforged.neoforge.items.wrapper.InvWrapper(this) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (slot == 1 && !isFinished(getItem(1))) return ItemStack.EMPTY;
                return super.extractItem(slot, amount, simulate);
            }
        };
    }
    public FossilSlicerBlockEntity(BlockPos pos, BlockState state) { super(LifeSanctuaryMod.FOSSIL_SLICER_ENTITY.get(), pos, state); }
    @Override public int getContainerSize() { return 2; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }
    public static boolean isInput(ItemStack stack) {
        return !stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals(LifeSanctuaryMod.MODID)
                && FossilMaterial.identify(stack).isPresent();
    }
    public static boolean isFinished(ItemStack stack) { return FossilSliceData.read(stack).isPresent(); }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot == 0 && isInput(stack); }
    @Override public int[] getSlotsForFace(Direction side) { return side == Direction.DOWN ? new int[]{1} : new int[]{0}; }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) { return side != Direction.DOWN && canPlaceItem(slot, stack); }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) { return side == Direction.DOWN && slot == 1 && isFinished(stack); }
    private boolean feeding;
    /** Inventory mutations wake feeding; an idle machine has no periodic work. */
    @Override public void setChanged() {
        if (!feeding && level != null && !level.isClientSide && items.get(1).isEmpty()) {
            var plan = FossilSlicingRules.planFeed(items.get(0).getCount(), items.get(1).getCount(), isInput(items.get(0)));
            if (plan.isPresent()) {
                feeding = true;
                var work = items.get(0).copyWithCount(plan.get().workCount());
                var remaining = items.get(0).copyWithCount(plan.get().remainingInput());
                items.set(0, remaining); items.set(1, work); workRevision++;
                feeding = false;
            }
        }
        super.setChanged();
    }
    @Override public void onLoad() { super.onLoad(); setChanged(); }
    @Override protected Component getDefaultName() { return Component.translatable("block.life_sanctuary.fossil_slicer"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return new FossilSlicerMenu(id, inventory, this); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) { super.saveAdditional(tag, registries); ContainerHelper.saveAllItems(tag, items, registries); }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); items = NonNullList.withSize(2, ItemStack.EMPTY); ContainerHelper.loadAllItems(tag, items, registries);
    }
    @Override public void clearContent() { super.clearContent(); setChanged(); }
    boolean busy(FossilSlicerMenu requester) { return operator != null && operator != requester && operator.ownsWork(); }
    boolean acquire(FossilSlicerMenu requester) { if (busy(requester)) return false; operator = requester; return true; }
    boolean ownedBy(FossilSlicerMenu menu) { return operator == menu; }
    void release(FossilSlicerMenu menu) { if (operator == menu) operator = null; }
    boolean canFit(ItemStack product) {
        return product.getCount() == 1 && FossilSlicingRules.canSlice(items.get(1).getCount(), isInput(items.get(1)));
    }
    /** Replace the one staged raw specimen with one finished slice on the server thread. */
    boolean finish(FossilSlicerMenu menu, ItemStack expected, ItemStack product) {
        if (level == null || level.isClientSide || !level.getServer().isSameThread() || !ownedBy(menu)
                || !ItemStack.isSameItemSameComponents(items.get(1), expected) || !canFit(product)) return false;
        items.set(1, product.copy()); setChanged(); return true;
    }
}
