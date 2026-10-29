package com.example.life_sanctuary;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** A placed gametophyte advances only while its chunk is loaded. */
public class EquisetumArvenseGametophyteBlock extends SupportedHorsetailBlock implements EnvironmentSensitivePlant {
    static final net.minecraft.world.phys.shapes.VoxelShape OUTLINE = Block.box(3.8, 0, 4.2, 11.8, 2.8, 12.2);
    public static final BooleanProperty WITHERING = BooleanProperty.create("withering");

    public EquisetumArvenseGametophyteBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WITHERING, false));
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) { builder.add(WITHERING); }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) { return OUTLINE; }

    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE);
    }

    @Override
    public boolean isEnvironmentSuitable(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        return !PlantEnvironment.isCold(level, pos);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, RandomSource random) {
        advance(level, pos);
    }

    static void advance(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        var data = HorsetailPopulations.get(level);
        if (!data.canGrowInClimate(level, pos)) return;
        var member = data.member(pos);
        if (HorsetailPopulations.isGametophyte(level.getBlockState(pos)) && member != null && member.source().canFinishCurrentCycle()) {
            var sprouts = LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get().defaultBlockState();
            if (!level.setBlock(pos, sprouts, Block.UPDATE_ALL)) {
                var current = level.getBlockState(pos);
                if (HorsetailPopulations.isGametophyte(current)) level.scheduleTick(pos, current.getBlock(), 200);
                return;
            }
            if (!level.getBlockState(pos).equals(sprouts) || !member.equals(data.member(pos))) {
                LifeSanctuaryMod.LOGGER.error("Horsetail gametophyte transition changed during callback at {}", pos);
                return;
            }
            data.transition(pos, member);
            data.setStage(pos, PlantStage.SPROUTS);
            level.scheduleTick(pos, LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get(), HorsetailSpreadRules.SPROUTS_TO_ADULT_TICKS);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && !HorsetailPopulations.isLivingHorsetail(newState) && level instanceof net.minecraft.server.level.ServerLevel server) {
            HorsetailPopulations.get(server).remove(pos);
            PlantWaterMonitoring.get(server).remove(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
