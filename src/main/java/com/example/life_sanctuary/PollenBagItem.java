package com.example.life_sanctuary;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.particles.ParticleTypes;

/** One item type for empty and species-filled pollen bags. */
public final class PollenBagItem extends Item {
    public PollenBagItem(Properties properties) {
        super(properties);
    }

    /** Returns the pollen species label, or {@code null} for an empty bag. */
    public static String species(ItemStack stack) {
        return SpeciesTaggedItemData.read(stack);
    }

    /** Creates a filled pollen bag while keeping the same item ID. */
    public static ItemStack filled(String speciesId) {
        ItemStack stack = new ItemStack(LifeSanctuaryMod.POLLEN_BAG.get());
        SpeciesTaggedItemData.write(stack, speciesId);
        return stack;
    }

    /** Pollen bags consume one item only when fertilization succeeds. */
    public static boolean consumesOnAcceptedUse() {
        return true;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        String speciesId = species(stack);
        if (speciesId == null) return InteractionResult.PASS;
        PollenBagEffect effect = ReproductiveBagEffects.pollen(speciesId);
        if (effect == null) return InteractionResult.PASS;
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.sidedSuccess(true);
        }
        ReproductiveBagUseResult result = ReproductiveBagUseHandler.usePollenBag(
                level, context.getClickedPos(), stack, effect);
        if (!result.success()) return InteractionResult.PASS;
        if (result.greenParticles()) {
            var center = context.getClickedPos().getCenter();
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y + 1.0D, center.z,
                    8, 0.25D, 0.35D, 0.25D, 0.02D);
        }
        return InteractionResult.sidedSuccess(false);
    }
}
