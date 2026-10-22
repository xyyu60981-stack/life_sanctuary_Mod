package com.example.life_sanctuary.compat.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Create returns no explicit items here when the list is a blacklist. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.filter.ListFilterItem", remap = false)
public interface CreateListFilterAccess {
    @Invoker("getFilterItems")
    ItemStack[] lifeSanctuary$allowedItems(ItemStack filter);
}
