package com.example.life_sanctuary;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Moss has no separate shared-gametophyte phase: the cabinet completes seedling -> adult. */
public final class LuminousMossCultivation {
    private LuminousMossCultivation() {}
    static CultivationRecipe recipe() {
        var species = LuminousMossRules.SPECIES;
        return new CultivationRecipe(species, species, List.of(new CultivationRecipe.Phase(
                LuminousMossRules.ADULT, species, LuminousMossRules.MIN_GROWTH, LuminousMossRules.MAX_GROWTH)));
    }
    public static void setup() {
        var species = LuminousMossRules.SPECIES;
        CultivationRecipes.register(recipe());
        CultivationProducts.registerStageTagged(species, species, new ItemStack(LifeSanctuaryMod.LUMINOUS_MOSS_ITEM.get()));
    }
    public static boolean accepts(ItemStack stack) {
        if (!stack.is(LifeSanctuaryMod.LUMINOUS_MOSS_ITEM.get())) return false;
        var completed = CultivatedStageData.read(stack).orElse(null);
        if (!matchesCompleted(completed)) return false;
        var recipe = CultivationRecipes.find(completed.species()).orElse(null);
        var phase = recipe == null ? null : recipe.phase(completed.phase()).orElse(null);
        return phase != null && phase.output().equals(LuminousMossRules.SPECIES)
                && CultivationProducts.stageTaggedItem(completed.species(), phase.output(), stack.getItem());
    }
    static boolean matchesCompleted(CultivatedStageData completed) {
        return completed != null && completed.species().equals(LuminousMossRules.SPECIES)
                && completed.phase().equals(LuminousMossRules.ADULT);
    }
}
