package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/** No ticker: vanilla menus and automation operate on this single inventory. */
public final class CollectorBlockEntity extends BaseContainerBlockEntity {
    private NonNullList<ItemStack> items;
    public final InvWrapper itemHandler = new InvWrapper(this);

    public CollectorBlockEntity(BlockPos pos, BlockState state) {
        super(LifeSanctuaryMod.COLLECTOR_BLOCK_ENTITY.get(), pos, state);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
    }

    @Override public int getContainerSize() {
        return getBlockState().is(LifeSanctuaryMod.COLLECTOR_PLUS_BLOCK.get()) ? 54 : 27;
    }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }
    /** Bounded inspection of the real slots; no container item or preview inventory is created. */
    public CollectorInventory.Summary summarizeContents() {
        int occupied = 0, spores = 0, pollen = 0;
        for (var item : items) {
            if (item.isEmpty()) continue;
            occupied++;
            if (ReproductiveBagKind.SPORE.isEmptyBag(item))
                spores = (int) Math.min(Integer.MAX_VALUE, (long) spores + item.getCount());
            if (ReproductiveBagKind.POLLEN.isEmptyBag(item))
                pollen = (int) Math.min(Integer.MAX_VALUE, (long) pollen + item.getCount());
        }
        return new CollectorInventory.Summary(occupied, getContainerSize(), spores, pollen);
    }
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return CollectorInventory.accepts(stack) && filter().allows(stack); }
    public CollectorFilter filter() {
        return CollectorFilter.read(components().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY));
    }
    public void setFilter(CollectorFilter filter) {
        var root = components().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        setComponents(net.minecraft.core.component.DataComponentMap.builder().addAll(components())
                .set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(filter.writeInto(root))).build());
        setChanged();
    }
    public boolean hasFilterConfiguration() {
        return components().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.EMPTY).copyTag().contains(CollectorFilter.KEY);
    }
    @Override public void clearContent() {
        if (isEmpty()) return;
        super.clearContent();
        // The base clearContent does not mark dirty. Persist bulk clears and notify comparators.
        setChanged();
    }
    @Override public void startOpen(Player player) {
        if (level != null && !level.isClientSide && !player.isSpectator())
            level.playSound(null, worldPosition, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
    }
    @Override protected Component getDefaultName() {
        return Component.translatable(getBlockState().getBlock().asItem().getDescriptionId());
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items = NonNullList.withSize(getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items, registries);
    }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new CollectorBlockMenu(id, inventory, this);
    }
}
