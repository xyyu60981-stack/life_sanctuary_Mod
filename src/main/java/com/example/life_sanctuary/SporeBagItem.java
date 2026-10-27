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
    public static ItemStack filled(String speciesId, SporeBagContents.Form form) {
        var stack = filled(speciesId); SporeBagContents.write(stack, form); return stack;
    }
    @Override public net.minecraft.network.chat.Component getName(ItemStack stack) {
        var form = SporeBagContents.form(stack).orElse(SporeBagContents.Form.NORMAL);
        return form == SporeBagContents.Form.NORMAL ? super.getName(stack)
                : net.minecraft.network.chat.Component.translatable("item.life_sanctuary.spore_bag." + form.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        String speciesId = species(stack);
        if (speciesId == null) return InteractionResult.PASS;
        var form = SporeBagContents.form(stack);
        if (form.isEmpty()) return InteractionResult.PASS;
        SporeBagEffect effect = ReproductiveBagEffects.spore(speciesId, form.get());
        if (effect == null) return InteractionResult.PASS;
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.sidedSuccess(true);
        }
        ReproductiveBagUseResult result = ReproductiveBagUseHandler.useSporeBag(
                level, context.getClickedPos(), stack, effect);
        return result.success() ? InteractionResult.sidedSuccess(false) : InteractionResult.PASS;
    }
}
