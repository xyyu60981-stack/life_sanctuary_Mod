package com.example.life_sanctuary;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ComponentItemHandler;

/**
 * Item capability backed by the same component used by spore harvesting.
 * Read current contents on every operation: caching would overwrite a harvest
 * performed between two automation operations. No ticking or world scans.
 */
public final class CollectorItemHandler extends ComponentItemHandler {
    private final ItemStack box;

    public CollectorItemHandler(ItemStack box) {
        super(box, DataComponents.CONTAINER, CollectorInventory.capacity(box));
        this.box = box;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return box.getCount() == 1 && CollectorInventory.accepts(stack);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0 || box.getCount() != 1) return ItemStack.EMPTY;
        // Allow removal of legacy contents even if the whitelist has changed.
        return super.extractItem(slot, amount, simulate);
    }
}
