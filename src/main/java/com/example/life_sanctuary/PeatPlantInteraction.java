package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Pure peat rules. Callers must persist the captured result for each mature structure/round. */
public final class PeatPlantInteraction {
    public static final TagKey<Block> PEAT = BlockTags.create(ResourceLocation.fromNamespaceAndPath("life_sanctuary", "peat"));
    public enum Bonus { NONE, SPORE_HARVEST, SEED_YIELD }
    private PeatPlantInteraction() {}

    public static Bonus capture(LevelReader level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof PeatResponsivePlant plant && plant.hasEligiblePeat(level, pos, state)) {
            return plant.peatBonus();
        }
        return Bonus.NONE;
    }

    public static int initialHarvests(int base, Bonus captured) {
        if (base < 0) throw new IllegalArgumentException("Negative harvest quota");
        return base == 0 ? 0 : Math.addExact(base, captured == Bonus.SPORE_HARVEST ? 1 : 0);
    }

    /** Add to an already rolled seed yield, never reroll it or increase harvest count. */
    public static int seedYield(int rolledYield, Bonus captured) {
        if (rolledYield < 0) throw new IllegalArgumentException("Negative seed yield");
        return Math.addExact(rolledYield, captured == Bonus.SEED_YIELD ? 1 : 0);
    }
}
