package com.example.life_sanctuary;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Item-form collector storage. No recursive inventory scans; mutations are committed once. */
public final class CollectorInventory {
    public static final TagKey<Item> COLLECTABLES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "collector_contents"));

    private CollectorInventory() {}

    /** Explicit whitelist: generic seed tags must not silently admit unrelated items. */
    public static boolean accepts(ItemStack stack) {
        return stack.isEmpty() || (capacity(stack) == 0
                && stack.canFitInsideContainerItems() && stack.is(COLLECTABLES));
    }
    public static int capacity(ItemStack stack) {
        if (stack.is(LifeSanctuaryMod.PORTABLE_COLLECTOR.get())) return 27;
        if (stack.is(LifeSanctuaryMod.PORTABLE_COLLECTOR_PLUS.get())) return 54;
        return 0;
    }

    /** Tooltip snapshot only; counts actual stacks, including legacy contents without modifying them. */
    public record Summary(int occupiedSlots, int capacity, int emptySporeBags, int emptyPollenBags) {}

    public static Summary summarize(ItemStack box) {
        var contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        int occupied = 0;
        int spores = 0;
        int pollen = 0;
        for (int i = 0; i < contents.getSlots(); i++) {
            ItemStack item = contents.getStackInSlot(i);
            if (item.isEmpty()) continue;
            occupied++;
            if (ReproductiveBagKind.SPORE.isEmptyBag(item)) spores += item.getCount();
            if (ReproductiveBagKind.POLLEN.isEmptyBag(item)) pollen += item.getCount();
        }
        return new Summary(occupied, capacity(box), spores, pollen);
    }

    /** Insert a copy, merge existing stacks first, and commit at most once. Returns accepted count. */
    public static int insert(ItemStack box, ItemStack incoming) {
        int size = capacity(box);
        if (size == 0 || box.getCount() != 1 || incoming.isEmpty() || !accepts(incoming) || !CollectorFilter.read(box).allows(incoming)) return 0;
        var contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (contents.getSlots() > size) return 0;
        NonNullList<ItemStack> slots = NonNullList.withSize(size, ItemStack.EMPTY);
        contents.copyInto(slots);
        int remaining = incoming.getCount();
        for (int i = 0; i < size && remaining > 0; i++) {
            ItemStack current = slots.get(i);
            if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, incoming)) continue;
            int amount = Math.min(remaining, Math.max(0, current.getMaxStackSize() - current.getCount()));
            current.grow(amount);
            remaining -= amount;
        }
        for (int i = 0; i < size && remaining > 0; i++) {
            if (!slots.get(i).isEmpty()) continue;
            int amount = Math.min(remaining, incoming.getMaxStackSize());
            slots.set(i, incoming.copyWithCount(amount));
            remaining -= amount;
        }
        int accepted = incoming.getCount() - remaining;
        if (accepted > 0) box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        return accepted;
    }

    public static boolean exchangeSporeBag(ItemStack box, ItemStack filled, boolean simulate) {
        return exchangeBag(box, ReproductiveBagKind.SPORE, filled, simulate);
    }

    public static boolean exchangePollenBag(ItemStack box, ItemStack filled, boolean simulate) {
        return exchangeBag(box, ReproductiveBagKind.POLLEN, filled, simulate);
    }

    /** Simulate and commit use the same algorithm. Failure never changes the original component. */
    public static boolean exchangeBag(ItemStack box, ReproductiveBagKind kind, ItemStack filled, boolean simulate) {
        if (filled.getCount() != 1 || !kind.isFilledBag(filled)) return false;
        int size = capacity(box);
        if (size == 0 || box.getCount() != 1 || !CollectorFilter.read(box).allows(filled)) return false;
        var contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (contents.getSlots() > size) return false; // Never truncate malformed/oversized contents.
        NonNullList<ItemStack> slots = NonNullList.withSize(size, ItemStack.EMPTY);
        contents.copyInto(slots);
        int emptyBag = -1;
        for (int i = 0; i < size; i++) {
            ItemStack item = slots.get(i);
            if (kind.isEmptyBag(item)) {
                if (emptyBag < 0) emptyBag = i;
                // A single bag frees its slot. Choosing an earlier larger stack can
                // otherwise incorrectly reject a full box that has room after exchange.
                if (item.getCount() == 1) {
                    emptyBag = i;
                    break;
                }
            }
        }
        if (emptyBag < 0) return false;
        slots.get(emptyBag).shrink(1);
        for (int i = 0; i < size; i++) {
            ItemStack item = slots.get(i);
            if (ItemStack.isSameItemSameComponents(item, filled) && item.getCount() < item.getMaxStackSize()) {
                item.grow(1);
                if (!simulate) box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
                return true;
            }
        }
        for (int i = 0; i < size; i++) {
            if (slots.get(i).isEmpty()) {
                slots.set(i, filled.copyWithCount(1));
                if (!simulate) box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
                return true;
            }
        }
        return false; // Original component and empty bag remain unchanged if output cannot fit.
    }

    /** Species-neutral precheck; the actual species is validated by atomic exchangeBag before debit. */
    public static boolean hasEmptyBag(ItemStack box, ReproductiveBagKind kind) {
        int size = capacity(box);
        if (size == 0 || box.getCount() != 1) return false;
        var contents = box.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (contents.getSlots() > size) return false;
        for (int i = 0; i < contents.getSlots(); i++) if (kind.isEmptyBag(contents.getStackInSlot(i))) return true;
        return false;
    }
}
