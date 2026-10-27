package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** 用快照选择攀援下一段；不读实际世界、不强制加载区块，不把根座写死为某个方块类。 */
public final class PlantClimbingPaths {
    private PlantClimbingPaths() {}
    public static final int NATURAL_MAXIMUM_PATH_LENGTH = 15;
    public enum Kind { FULL_FACE, BYPASS_PARTIAL_OBSTACLE }
    public enum Status { SELECTED, BLOCKED, MISSING_SNAPSHOT }
    /** 物种可提供只读探针；查询涉及的全部世界状态必须记录为dependencies，缺数据须抛MissingSnapshot。 */
    public interface Probe {
        boolean canOccupy(BlockPos pos);
        boolean hasFullSupportFace(BlockPos support, Direction face);
        boolean isPartialObstacle(BlockPos pos);
        Map<BlockPos, BlockState> dependencies();
    }
    public record Candidate(Kind kind, List<BlockPos> newStems, BlockPos support, Direction face) {
        public Candidate {
            Objects.requireNonNull(kind); Objects.requireNonNull(face);
            newStems = newStems.stream().map(BlockPos::immutable).toList();
            support = support.immutable();
            if (newStems.size() != (kind == Kind.FULL_FACE ? 1 : 2)
                    || !newStems.getLast().equals(support.relative(face)))
                throw new IllegalArgumentException("Climbing path must end on its specified support face");
        }
    }
    public record Selection(Status status, Optional<Candidate> candidate, Map<BlockPos, BlockState> dependencies,
            Optional<BlockPos> missing) {
        public Selection {
            Objects.requireNonNull(status); Objects.requireNonNull(candidate); Objects.requireNonNull(missing);
            dependencies = Map.copyOf(dependencies);
            if ((status == Status.SELECTED) != candidate.isPresent() || (status == Status.MISSING_SNAPSHOT) != missing.isPresent())
                throw new IllegalArgumentException("Invalid climbing selection");
        }
    }

    /**
     * 候选顺序只打破同类平局，完整面总优先于越过；hostBlocks由物种定义合法攀援物。
     * 路径总长上限由调用者明确提供，天然路径使用15，不擅自把此限额用于人工全生命周期。
     * 返回dependencies须合入结构事务observed，包含用于判断优先级而读过的受阻候选。
     */
    public static Selection select(BlockPos from, Set<BlockPos> existingStems, int maximumPathLength,
            List<Candidate> candidates, Map<BlockPos, BlockState> observed, int minimumY, int height,
            Predicate<BlockState> hostBlocks) {
        Objects.requireNonNull(from); Objects.requireNonNull(hostBlocks);
        return select(from, existingStems, maximumPathLength, candidates, new Snapshot(observed, minimumY, height, hostBlocks));
    }

    public static Selection select(BlockPos from, Set<BlockPos> existingStems, int maximumPathLength,
            List<Candidate> candidates, Probe snapshot) {
        Objects.requireNonNull(from); Objects.requireNonNull(snapshot);
        var existing = Set.copyOf(existingStems);
        if (!existing.contains(from) || maximumPathLength < existing.size())
            throw new IllegalArgumentException("Invalid existing climbing path or allowance");
        var options = List.copyOf(candidates);
        try {
            for (var kind : Kind.values()) {
                for (var candidate : options) {
                    if (candidate.kind() != kind || (long) existing.size() + candidate.newStems().size() > maximumPathLength) continue;
                    validateGeometry(from, candidate);
                    boolean clear = true;
                    for (var pos : candidate.newStems()) {
                        if (existing.contains(pos) || !snapshot.canOccupy(pos)) { clear = false; break; }
                    }
                    if (!clear) continue;
                    if (!snapshot.hasFullSupportFace(candidate.support(), candidate.face())) continue;
                    if (kind == Kind.BYPASS_PARTIAL_OBSTACLE) {
                        // 先直立一格，再水平越过挡在原高度的非完整碰撞体。
                        var obstacle = candidate.newStems().getLast().below();
                        if (!snapshot.isPartialObstacle(obstacle)) continue;
                    }
                    return new Selection(Status.SELECTED, Optional.of(candidate), snapshot.dependencies(), Optional.empty());
                }
            }
            return new Selection(Status.BLOCKED, Optional.empty(), snapshot.dependencies(), Optional.empty());
        } catch (MissingSnapshot missing) {
            return new Selection(Status.MISSING_SNAPSHOT, Optional.empty(), snapshot.dependencies(), Optional.of(missing.pos));
        }
    }

    private static void validateGeometry(BlockPos from, Candidate candidate) {
        if (candidate.newStems().contains(candidate.support())) throw new IllegalArgumentException("Climbing host cannot be a new path segment");
        var seen = new HashSet<BlockPos>();
        seen.add(from);
        var previous = from;
        for (var pos : candidate.newStems()) {
            if (!seen.add(pos) || Math.abs((long) pos.getX() - previous.getX()) + Math.abs((long) pos.getY() - previous.getY())
                    + Math.abs((long) pos.getZ() - previous.getZ()) != 1)
                throw new IllegalArgumentException("Climbing segments must form a simple cardinal path");
            previous = pos;
        }
        if (candidate.kind() == Kind.BYPASS_PARTIAL_OBSTACLE
                && (!candidate.newStems().getFirst().equals(from.above())
                    || candidate.newStems().getLast().getY() != from.getY() + 1))
            throw new IllegalArgumentException("Climbing bypass must rise exactly once before moving sideways");
    }

    public static final class MissingSnapshot extends RuntimeException {
        final BlockPos pos;
        public MissingSnapshot(BlockPos pos) { this.pos = pos.immutable(); }
    }
    /** 动态形状只能读给定快照；缺邻格时明确暂停，不能偷偷查询／加载实际世界。 */
    private static final class Snapshot implements BlockGetter, Probe {
        final Map<BlockPos, BlockState> states;
        final Map<BlockPos, BlockState> reads = new HashMap<>();
        final int minimumY, height;
        final Predicate<BlockState> hostBlocks;
        Snapshot(Map<BlockPos, BlockState> states, int minimumY, int height, Predicate<BlockState> hostBlocks) {
            var copied = new HashMap<BlockPos, BlockState>();
            states.forEach((pos, state) -> copied.put(pos.immutable(), Objects.requireNonNull(state)));
            this.states = Map.copyOf(copied);
            if (height <= 0) throw new IllegalArgumentException("Invalid build height");
            Math.addExact(minimumY, height);
            this.minimumY = minimumY; this.height = height;
            this.hostBlocks = hostBlocks;
        }
        @Override public boolean canOccupy(BlockPos pos) {
            if (isOutsideBuildHeight(pos)) return false;
            var state = getBlockState(pos);
            return !state.hasBlockEntity() && state.getFluidState().isEmpty() && (state.isAir() || state.canBeReplaced());
        }
        @Override public boolean hasFullSupportFace(BlockPos pos, Direction face) {
            if (isOutsideBuildHeight(pos)) return false;
            var state = getBlockState(pos);
            // 支撑不被替换；含水或带方块实体不应仅因此丧失完整外表面资格。
            return hostBlocks.test(state) && state.isFaceSturdy(this, pos, face, SupportType.FULL);
        }
        @Override public boolean isPartialObstacle(BlockPos pos) {
            if (isOutsideBuildHeight(pos)) return false;
            var state = getBlockState(pos);
            if (canOccupy(pos)) return false;
            var shape = state.getCollisionShape(this, pos);
            return !shape.isEmpty() && !Block.isShapeFullBlock(shape);
        }
        @Override public Map<BlockPos, BlockState> dependencies() { return Map.copyOf(reads); }
        @Override public BlockState getBlockState(BlockPos pos) {
            var state = states.get(pos);
            if (state == null) throw new MissingSnapshot(pos);
            reads.put(pos.immutable(), state);
            return state;
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            if (getBlockState(pos).hasBlockEntity()) throw new MissingSnapshot(pos);
            return null;
        }
        @Override public int getHeight() { return height; }
        @Override public int getMinBuildHeight() { return minimumY; }
    }
}
