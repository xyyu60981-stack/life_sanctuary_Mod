package com.example.life_sanctuary;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.FarmlandWaterManager;

/** Vanilla farmland hydration, but every degradation path returns peat. No growth-speed bonus. */
public final class PeatFarmlandBlock extends FarmBlock {
    // FarmBlock fixes codec() to MapCodec<FarmBlock>; generic codecs are not covariant.
    // Keep our constructor so decoding still creates peat farmland, not vanilla farmland.
    public static final MapCodec<FarmBlock> CODEC = simpleCodec(PeatFarmlandBlock::new);
    public PeatFarmlandBlock(Properties properties) { super(properties); }
    @Override public MapCodec<FarmBlock> codec() { return CODEC; }

    @Override
    public net.neoforged.neoforge.common.util.TriState canSustainPlant(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos soilPosition,
            net.minecraft.core.Direction facing, BlockState plant) {
        if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(plant.getBlock()).getNamespace()
                .equals(LifeSanctuaryMod.MODID)) return net.neoforged.neoforge.common.util.TriState.FALSE;
        if (facing == net.minecraft.core.Direction.UP && (plant.getBlock() instanceof net.minecraft.world.level.block.CropBlock
                || plant.getBlock() instanceof net.minecraft.world.level.block.StemBlock
                || plant.getBlock() instanceof net.minecraft.world.level.block.AttachedStemBlock)) {
            return net.neoforged.neoforge.common.util.TriState.TRUE;
        }
        return net.neoforged.neoforge.common.util.TriState.DEFAULT;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().canSurvive(context.getLevel(), context.getClickedPos())
                ? defaultBlockState() : LifeSanctuaryMod.PEAT.get().defaultBlockState();
    }

    private static void revert(Level level, BlockPos pos, BlockState state, Entity entity) {
        BlockState replacement = pushEntitiesUp(state, LifeSanctuaryMod.PEAT.get().defaultBlockState(), level, pos);
        level.setBlockAndUpdate(pos, replacement);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(entity, replacement));
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.canSurvive(level, pos)) revert(level, pos, state, null);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        boolean hydrated = level.isRainingAt(pos.above()) || FarmlandWaterManager.hasBlockWaterTicket(level, pos);
        for (BlockPos candidate : BlockPos.betweenClosed(pos.offset(-4, 0, -4), pos.offset(4, 1, 4))) {
            if (hydrated) break;
            if (!level.getChunkSource().hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) continue;
            hydrated = state.canBeHydrated(level, pos, level.getFluidState(candidate), candidate);
        }
        int moisture = state.getValue(MOISTURE);
        if (hydrated) {
            if (moisture < MAX_MOISTURE) level.setBlock(pos, state.setValue(MOISTURE, MAX_MOISTURE), 2);
        } else if (moisture > 0) {
            level.setBlock(pos, state.setValue(MOISTURE, moisture - 1), 2);
        } else if (!level.getBlockState(pos.above()).is(BlockTags.MAINTAINS_FARMLAND)) {
            revert(level, pos, state, null);
        }
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float distance) {
        if (!level.isClientSide && distance >= 3.0F && CommonHooks.onFarmlandTrample(level, pos,
                LifeSanctuaryMod.PEAT.get().defaultBlockState(), distance, entity)) {
            revert(level, pos, state, entity);
        }
        // Calling FarmBlock.fallOn would perform a second trample check and replace peat with dirt.
        entity.causeFallDamage(distance, 1.0F, level.damageSources().fall());
    }
}
