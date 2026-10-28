package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Applies species effects and performs the shared post-use inventory update. */
public final class ReproductiveBagUseHandler {
    private ReproductiveBagUseHandler() {}

    /**
     * Uses a tagged spore bag. The stack is consumed only after an accepted effect.
     */
    public static ReproductiveBagUseResult useSporeBag(ServerLevel level, BlockPos target, ItemStack stack,
            SporeBagEffect effect) {
        String speciesId = SporeBagItem.species(stack);
        var form = SporeBagContents.form(stack);
        boolean differentiated = PlantSporeTraits.species(speciesId).map(PlantSporeTraits::find)
                .map(PlantSporeTraits.Profile::differentiatedSpores).orElse(false);
        if (effect == null || speciesId == null || form.isEmpty() || differentiated
                && effect != ReproductiveBagEffects.spore(speciesId, form.get())) return ReproductiveBagUseResult.rejected();
        ReproductiveBagUseResult result = effect.use(level, target, speciesId);
        consumeIfRequested(stack, result);
        return result;
    }

    /** Uses a tagged pollen bag and returns whether green particle feedback is requested. */
    public static ReproductiveBagUseResult usePollenBag(ServerLevel level, BlockPos target, ItemStack stack,
            PollenBagEffect effect) {
        String speciesId = PollenBagItem.species(stack);
        if (speciesId == null) return ReproductiveBagUseResult.rejected();
        ReproductiveBagUseResult result = effect.use(level, target, speciesId);
        consumeIfRequested(stack, result);
        return result;
    }

    private static void consumeIfRequested(ItemStack stack, ReproductiveBagUseResult result) {
        if (result.success() && result.consumeOne()) stack.shrink(1);
    }
}
