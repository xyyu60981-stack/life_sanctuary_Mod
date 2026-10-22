package com.example.life_sanctuary.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.world.item.ItemStack;

/** String target keeps Create optional; no Create classes are linked by the base mod. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.filter.FilterItemStack", remap = false)
public interface CreateFilterAccess {
    @Invoker("isEmpty")
    boolean lifeSanctuary$isEmpty();

    @Invoker("item")
    ItemStack lifeSanctuary$item();
}
