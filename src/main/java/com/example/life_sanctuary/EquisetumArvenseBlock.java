package com.example.life_sanctuary;

import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** Adult horsetail: two linked blocks share one stem state and one population base. */
public class EquisetumArvenseBlock extends SupportedHorsetailBlock implements EnvironmentSensitivePlant, PeatResponsivePlant {
    @Override
    public PeatPlantInteraction.Bonus peatBonus() { return PeatPlantInteraction.Bonus.SPORE_HARVEST; }
    public static final IntegerProperty NUTRITIONAL_STEMS = IntegerProperty.create("nutritional_stems", 1, 3);
    public static final BooleanProperty SPORE_STEM = BooleanProperty.create("spore_stem");
    public static final BooleanProperty WITHERING = BooleanProperty.create("withering");
    /** Visual state reserved for the growing adult variant; sprouts remain a separate one-block stage. */
    public static final BooleanProperty GROWING = BooleanProperty.create("growing");
    public static final EnumProperty<DoubleBlockHalf> PART = EnumProperty.create("part", DoubleBlockHalf.class);

    public EquisetumArvenseBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(NUTRITIONAL_STEMS, 1).setValue(SPORE_STEM, false)
                .setValue(WITHERING, false).setValue(GROWING, false).setValue(PART, DoubleBlockHalf.LOWER));
    }

    public static BlockPos base(BlockState state, BlockPos pos) {
        return state.getValue(PART) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    /** Read-only interaction gate: both halves must agree before any harvest or retrieval. */
    public static boolean hasMatchingHalves(LevelReader level, BlockPos base, BlockState lower) {
        return lower.getBlock() instanceof EquisetumArvenseBlock
                && lower.getValue(PART) == DoubleBlockHalf.LOWER
                && level.getBlockState(base.above()).equals(lower.setValue(PART, DoubleBlockHalf.UPPER));
    }

    /** Both model halves must choose the same weighted height variant. */
    @Override
    @SuppressWarnings("deprecation") // Vanilla model seed algorithm; intentionally shared by both halves.
    protected long getSeed(BlockState state, BlockPos pos) {
        return net.minecraft.util.Mth.getSeed(base(state, pos));
    }

    public static boolean canFitAdult(LevelReader level, BlockPos pos) {
        BlockPos upperPos = pos.above();
        if (level.isOutsideBuildHeight(upperPos)) return false;
        if (level instanceof ServerLevel server && (!server.getWorldBorder().isWithinBounds(upperPos)
                || !server.getChunkSource().hasChunk(upperPos.getX() >> 4, upperPos.getZ() >> 4))) return false;
        BlockState upper = level.getBlockState(upperPos);
        return upper.canBeReplaced() && upper.getFluidState().isEmpty();
    }

    /** Sprouts remain one block. Convert only when the second adult block can actually fit. */
    public static boolean growFromSprouts(ServerLevel level, BlockPos pos, boolean growing) {
        BlockState sprouts = level.getBlockState(pos);
        if (!sprouts.is(LifeSanctuaryMod.EQUISETUM_ARVENSE_SPROUTS.get())
                || !sprouts.canSurvive(level, pos)) return false;
        BlockPos upperPos = pos.above();
        if (!canFitAdult(level, pos)) return false;
        BlockState previousUpper = level.getBlockState(upperPos);
        var adult = (EquisetumArvenseBlock) LifeSanctuaryMod.EQUISETUM_ARVENSE.get();
        BlockState lower = adult.defaultBlockState().setValue(GROWING, growing);
        BlockState upper = lower.setValue(PART, DoubleBlockHalf.UPPER);
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        if (!level.setBlock(upperPos, upper, quiet)) return false;
        // Placement callbacks may have changed the seedling or its support. Never overwrite that change.
        if (!level.getBlockState(pos).equals(sprouts) || !sprouts.canSurvive(level, pos)
                || !level.getBlockState(upperPos).equals(upper)) {
            if (level.getBlockState(upperPos).equals(upper)) level.setBlock(upperPos, previousUpper, quiet);
            return false;
        }
        if (!level.setBlock(pos, lower, Block.UPDATE_ALL)) {
            if (level.getBlockState(upperPos).equals(upper)) level.setBlock(upperPos, previousUpper, quiet);
            return false;
        }
        level.updateNeighborsAt(upperPos, adult);
        // Do not advance the saved stage if a neighbor callback removed either half.
        return level.getBlockState(pos).equals(lower) && level.getBlockState(upperPos).equals(upper);
    }

    /** Reproductive generation is a one-shot opportunity after harvest. */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state, Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
            net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
        BlockPos base = base(state, pos);
        BlockState lower = level.getBlockState(base);
        if (!lower.is(this) || !hasMatchingHalves(level, base, lower)
                || !lower.getValue(SPORE_STEM) || !CollectorSporeHarvestAccess.canInteract(player, hand))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (player.isSpectator() || !level.mayInteract(player, base)
                || !level.mayInteract(player, base.above())
                || !player.mayUseItemAt(base, hit.getDirection(), stack)
                || !player.mayUseItemAt(base.above(), hit.getDirection(), stack))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel server && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            return HorsetailPopulations.get(server).tryHarvestSporeStem(base, new CollectorSporeHarvestAccess(serverPlayer, hand))
                    ? net.minecraft.world.ItemInteractionResult.sidedSuccess(false)
                    : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(true);
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return HorsetailStemInteraction.shape(state);
    }

    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(BlockState state,
            net.minecraft.world.phys.HitResult target, LevelReader level, BlockPos pos,
            net.minecraft.world.entity.player.Player player) {
        return HorsetailStemInteraction.pickStack(state, pos, target);
    }

    public static boolean mayGenerateSporeStem(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(LifeSanctuaryMod.EQUISETUM_ARVENSE.get())
                && HorsetailPopulations.get(level).mayGenerateSporeStem(base(state, pos));
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(PART) == DoubleBlockHalf.UPPER) {
            BlockState lower = level.getBlockState(pos.below());
            return lower.is(this) && lower.getValue(PART) == DoubleBlockHalf.LOWER;
        }
        return level.getBlockState(pos.below()).is(LifeSanctuaryMod.HORSETAIL_SUBSTRATE);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction == (state.getValue(PART) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN)
                && (!neighbor.is(this) || neighbor.getValue(PART) == state.getValue(PART)))
            return Blocks.AIR.defaultBlockState();
        if (direction == Direction.DOWN && state.getValue(PART) == DoubleBlockHalf.UPPER)
            return neighbor.setValue(PART, DoubleBlockHalf.UPPER);
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public boolean isEnvironmentSuitable(LevelReader level, BlockPos pos, BlockState state) {
        return !PlantEnvironment.isCold(level, base(state, pos));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NUTRITIONAL_STEMS, SPORE_STEM, WITHERING, GROWING, PART);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && state.getValue(PART) == DoubleBlockHalf.LOWER && state.getValue(SPORE_STEM)
                && (!oldState.hasProperty(SPORE_STEM) || !oldState.getValue(SPORE_STEM))
                && level instanceof ServerLevel serverLevel) {
            HorsetailPopulations.get(serverLevel).initializeSporeStem(serverLevel, pos);
        }
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(PART) != DoubleBlockHalf.LOWER) return;
        HorsetailPopulations data = HorsetailPopulations.get(level);
        if (!data.canGrowInClimate(level, pos)) return;
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
        } else if (state.getValue(GROWING)) {
            level.setBlock(pos, state.setValue(GROWING, false), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos otherPos = state.getValue(PART) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            BlockState other = level.getBlockState(otherPos);
            if (other.is(this) && other.getValue(PART) != state.getValue(PART))
                level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            if (!level.isClientSide && !HorsetailPopulations.isLivingHorsetail(newState)
                    && level instanceof ServerLevel server
                    && !HorsetailPopulations.isLivingHorsetail(server.getBlockState(base(state, pos)))) {
                BlockPos base = base(state, pos);
                HorsetailPopulations.get(server).remove(base);
                PlantWaterMonitoring.get(server).remove(base);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
