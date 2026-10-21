package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Stackable generic dead horsetail material, similar to sugar cane placement. */
public class DeadEquisetalesBlock extends Block {
    public DeadEquisetalesBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(this) || below.is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE);
    }
}
