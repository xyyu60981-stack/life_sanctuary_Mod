package com.example.life_sanctuary;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/** Server-side entry for a real cultivated gametophyte item. */
public final class CultivatedGametophyteUse {
    private CultivatedGametophyteUse() {}
    @FunctionalInterface public interface Planner {
        /** Read-only species/substrate planning. Check chunk availability before reading blocks. */
        java.util.Optional<PlantStructurePlan> prepare(ServerLevel level, UseOnContext context, CultivatedStageData completed);
    }

    public static InteractionResult use(UseOnContext context, Planner planner) {
        java.util.Objects.requireNonNull(planner);
        var stack = context.getItemInHand();
        var completed = CultivatedStageData.read(stack).orElse(null);
        if (completed == null || context.getPlayer() == null
                || !CultivatedGametophyteMapping.accepts(stack, completed)) return InteractionResult.PASS;
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.sidedSuccess(true);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        if (!level.mayInteract(context.getPlayer(), context.getClickedPos())
                || !context.getPlayer().mayUseItemAt(context.getClickedPos().relative(context.getClickedFace()), context.getClickedFace(), stack))
            return InteractionResult.FAIL;
        var expected = stack.copy();
        var homosporousStage = CultivatedGametophyteMapping.homosporousStage(expected, completed);
        PlantStructurePlan plan;
        HeterosporeCycle cycle = null;
        try {
            plan = java.util.Objects.requireNonNull(planner.prepare(level, context, completed)).orElse(null);
            if (plan == null || !plan.expectedIndividual().species().equals(completed.species())
                    || !same(expected, context.getItemInHand())) return InteractionResult.PASS;
            // A species may place several blocks; the click permission alone cannot authorize all of them.
            for (var changedPos : plan.changes().keySet())
                if (!level.mayInteract(context.getPlayer(), changedPos)) return InteractionResult.FAIL;
            if (homosporousStage.isPresent()) {
                if (!homosporousStage.orElseThrow().equals(plan.expectedIndividual().stage())
                        || !HomosporousGametophyteBirths.valid(plan, true)) return InteractionResult.PASS;
            } else {
                cycle = CultivatedGametophyteMapping.start(expected, completed.species(), level.random).orElse(null);
                if (cycle == null) return InteractionResult.PASS;
            }
            if (!same(expected, context.getItemInHand())) return InteractionResult.PASS;
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cultivated gametophyte planning failed for {}", completed.species(), failure);
            return InteractionResult.FAIL;
        }
        var result = homosporousStage.isPresent() ? HomosporousGametophyteBirths.placeCultivated(level, plan)
                : switch (HeterosporeBirths.placeCultivated(level, plan, cycle)) {
                    case PLANTED -> ReproductiveBagUseResult.consumed(false);
                    case REJECTED -> ReproductiveBagUseResult.rejected();
                    case RECOVERY_REQUIRED -> ReproductiveBagUseResult.recovery();
                };
        if (!result.success()) return result.recoveryRequired() ? InteractionResult.FAIL : InteractionResult.PASS;
        if (!same(expected, context.getItemInHand())) {
            freeze(level, plan.expectedIndividual());
            return InteractionResult.FAIL;
        }
        try {
            context.getItemInHand().shrink(1);
            var remaining = context.getItemInHand();
            if (expected.getCount() == 1 ? !remaining.isEmpty()
                    : remaining.getCount() != expected.getCount() - 1 || !ItemStack.isSameItemSameComponents(expected, remaining)) {
                freeze(level, plan.expectedIndividual());
                return InteractionResult.FAIL;
            }
        } catch (RuntimeException failure) {
            freeze(level, plan.expectedIndividual());
            LifeSanctuaryMod.LOGGER.error("Cultivated item consumption failed for " + plan.expectedIndividual().id(), failure);
            return InteractionResult.FAIL;
        }
        return InteractionResult.sidedSuccess(false);
    }
    private static void freeze(ServerLevel level, PlantIndividualState expected) {
        try {
            HeterosporeWorldData.get(level).freezeForRecovery(expected);
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot freeze cultivated gametophyte for {}", expected.id(), failure);
        }
        try {
            PlantLifecycle.setPauseReason(PlantIndividuals.get(level), expected.id(), "cultivated_item_changed_error", true);
        } catch (RuntimeException failure) {
            LifeSanctuaryMod.LOGGER.error("Cannot mark cultivated item failure for {}", expected.id(), failure);
        }
        LifeSanctuaryMod.LOGGER.error("Cultivated item transaction requires recovery for {}", expected.id());
    }
    private static boolean same(ItemStack expected, ItemStack actual) {
        return !actual.isEmpty() && expected.getCount() == actual.getCount() && ItemStack.isSameItemSameComponents(expected, actual);
    }
}
