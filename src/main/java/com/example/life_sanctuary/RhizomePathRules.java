package com.example.life_sanctuary;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;

/** 横向和竖向地下茎共用的路径白名单，不使用宽泛的 canBeReplaced。 */
public final class RhizomePathRules {
    private RhizomePathRules() {}

    public static boolean canConvert(BlockState state) {
        return !state.hasBlockEntity()
                && (state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.WATER))
                && (isAirLike(state) || state.is(Blocks.WATER) || state.is(Blocks.MOSS_BLOCK)
                        || state.is(BlockTags.MOSS_REPLACEABLE));
    }

    /** 这些干燥装饰按空气处理，但实际替换仍须发出方块更新；不含任意可替换方块。 */
    public static boolean isAirLike(BlockState state) {
        return state.isAir() || (!state.hasBlockEntity() && state.getFluidState().isEmpty()
                && (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                    || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                    || state.is(BlockTags.SMALL_FLOWERS) || state.is(BlockTags.TALL_FLOWERS)
                    || state.is(Blocks.PINK_PETALS) || state.getBlock() instanceof CarpetBlock
                    || state.is(Blocks.MOSS_CARPET)));
    }
}
