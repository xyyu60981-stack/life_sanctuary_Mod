package com.example.life_sanctuary;

import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** Adult horsetail block. One block stores visible stem and spore-stem state. */
public class EquisetumArvenseBlock extends Block implements EnvironmentSensitivePlant {
    public static final IntegerProperty NUTRITIONAL_STEMS = IntegerProperty.create("nutritional_stems", 1, 3);
    public static final BooleanProperty SPORE_STEM = BooleanProperty.create("spore_stem");
    public static final BooleanProperty WITHERING = BooleanProperty.create("withering");

    public EquisetumArvenseBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(NUTRITIONAL_STEMS, 1).setValue(SPORE_STEM, false).setValue(WITHERING, false));
    }

    /** Reproductive generation is a one-shot opportunity after harvest. */
    public static boolean mayGenerateSporeStem(ServerLevel level, BlockPos pos) {
        return HorsetailPopulations.get(level).mayGenerateSporeStem(pos);
    }

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
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NUTRITIONAL_STEMS, SPORE_STEM, WITHERING);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        HorsetailPopulations data = HorsetailPopulations.get(level);
        var member = data.member(pos);
        if (member == null || !member.source().canExpand()) return;
        data.beginOrGetSpreadQuota(pos, random);
        boolean spread = false;
        while (data.remainingSpread(pos) > 0 && data.trySpreadOnce(level, pos)) {
            // Consume all immediately available offspring slots.
            spread = true;
        }
        if (spread) data.resetSpreadRetry(pos);
        if (data.remainingSpread(pos) > 0) {
            level.scheduleTick(pos, this, spread
                    ? 100 + random.nextInt(101)
                    : data.nextSpreadRetryDelay(pos, random));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel server) HorsetailPopulations.get(server).remove(pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
