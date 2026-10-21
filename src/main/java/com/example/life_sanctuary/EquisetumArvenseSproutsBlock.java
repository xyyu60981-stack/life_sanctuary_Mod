package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.LevelReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BonemealableBlock;

/** The growing horsetail stage advances only while its chunk is loaded. */
public class EquisetumArvenseSproutsBlock extends Block implements BonemealableBlock, EnvironmentSensitivePlant {
    public static final BooleanProperty WITHERING = BooleanProperty.create("withering");

    public EquisetumArvenseSproutsBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WITHERING, false));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) { builder.add(WITHERING); }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE);
    }

    @Override
    public boolean isEnvironmentSuitable(LevelReader level, BlockPos pos, BlockState state) {
        return !PlantEnvironment.isDry(level, pos);
    }

    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state) { return true; }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) { return random.nextFloat() < 0.60F; }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        var data = HorsetailPopulations.get(level);
        var member = data.member(pos);
        level.setBlock(pos, LifeSanctuaryMod.EQUISETUM_ARVENSE.get().defaultBlockState(), Block.UPDATE_ALL);
        data.transition(pos, member);
        data.setStage(pos, PlantStage.ADULT);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, RandomSource random) {
        var data = HorsetailPopulations.get(level);
        var member = data.member(pos);
        if (level.getBlockState(pos).is(this) && member != null && member.source().canFinishCurrentCycle()) {
            level.setBlock(pos, LifeSanctuaryMod.EQUISETUM_ARVENSE.get().defaultBlockState(), Block.UPDATE_ALL);
            data.transition(pos, member);
            data.setStage(pos, PlantStage.ADULT);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel server) HorsetailPopulations.get(server).remove(pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
