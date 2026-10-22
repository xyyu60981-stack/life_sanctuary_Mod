package com.example.life_sanctuary;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

/** One item type for empty and species-filled spore bags. */
public final class SporeBagItem extends Item {
    public SporeBagItem(Properties properties) {
        super(properties);
    }

    /** Returns the species label, or {@code null} for an empty bag. */
    public static String species(ItemStack stack) {
        return SpeciesTaggedItemData.read(stack);
    }

    /** Writes the species label into a copy while preserving the same item ID. */
    public static ItemStack filled(String speciesId) {
        ItemStack stack = new ItemStack(LifeSanctuaryMod.SPORE_BAG.get());
        SpeciesTaggedItemData.write(stack, speciesId);
        return stack;
    }

    /** Spore bags consume one item only when the species effect accepts the target. */
    public static boolean consumesOnAcceptedUse() {
        return true;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        String speciesId = species(stack);
        if (speciesId == null) return InteractionResult.PASS;
        SporeBagEffect effect = ReproductiveBagEffects.spore(speciesId);
        if (effect == null) return InteractionResult.PASS;
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.sidedSuccess(true);
        }
        ReproductiveBagUseResult result = ReproductiveBagUseHandler.useSporeBag(
                level, context.getClickedPos(), stack, effect);
        return result.success() ? InteractionResult.sidedSuccess(false) : InteractionResult.PASS;
    }
}
