package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** 出苗的纯结构提案；地下系统和幼丛分属不同身份，不能直接当作单株结构提交。 */
public final class RhizomeShootStructure {
    private RhizomeShootStructure() {}

    public enum Kind { RHIZOME, THIN_STEM, SHOOT, DECORATION_CLEANUP, GROWTH_POINT_UPDATE }

    /** 物种依据原流体状态提供实际方块；普通外形也可能需要保留流水。 */
    @FunctionalInterface
    public interface Materializer {
        BlockState state(Kind kind, BlockPos position, BlockState before, boolean waterloggedShoot);
    }

    public record Part(Kind kind, BlockState before, BlockState after) {
        public Part {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
            boolean cleanup = kind == Kind.DECORATION_CLEANUP;
            if (kind == Kind.GROWTH_POINT_UPDATE
                    && (!before.hasProperty(RhizomeBlock.GROWTH_POINT)
                        || !after.equals(RhizomeGrowthPoints.mark(before, false))))
                throw new IllegalArgumentException("Old growth point update must only clear its marker");
            if (cleanup && (!RhizomePathRules.isAirLike(before) || before.isAir() || !after.isAir()))
                throw new IllegalArgumentException("Cleanup must remove an air-like decoration");
            if (before.hasBlockEntity() || after.hasBlockEntity() || (!cleanup && after.isAir()) || after.is(Blocks.WATER))
                throw new IllegalArgumentException("Plant part must be a real block without block entity data");
            var fluid = before.getFluidState();
            if ((!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) || !fluid.equals(after.getFluidState()))
                throw new IllegalArgumentException("Shoot structure must preserve the exact fluid state");
        }
    }

    public record Proposal(RhizomeShootPath.Path path, Map<BlockPos, Part> parts) {
        public Proposal {
            Objects.requireNonNull(path);
            Map<BlockPos, Part> copy = new LinkedHashMap<>();
            parts.forEach((pos, part) -> copy.put(pos.immutable(), Objects.requireNonNull(part)));
            Map<BlockPos, Kind> required = requiredParts(path);
            if (!copy.keySet().equals(required.keySet()))
                throw new IllegalArgumentException("Incomplete shoot structure");
            copy.forEach((pos, part) -> {
                if (part.kind() != required.get(pos) || !part.before().equals(path.observed().get(pos)))
                    throw new IllegalArgumentException("Shoot part does not match path snapshot");
            });
            parts = Map.copyOf(copy);
        }
    }

    /** 不读取世界、不抽随机数、不扣名额；提交者仍须复核完整 observed 和系统快照。 */
    public static Proposal prepare(RhizomeShootPath.Path path, Materializer materializer) {
        Objects.requireNonNull(materializer);
        Map<BlockPos, Part> parts = new LinkedHashMap<>();
        requiredParts(path).forEach((pos, kind) -> {
            BlockState before = Objects.requireNonNull(path.observed().get(pos), "Missing path snapshot");
            BlockState after = kind == Kind.DECORATION_CLEANUP ? Blocks.AIR.defaultBlockState()
                    : materializer.state(kind, pos, before, kind == Kind.SHOOT && path.waterloggedShoot());
            if (kind == Kind.RHIZOME) after = RhizomeGrowthPoints.mark(after, false);
            parts.put(pos, new Part(kind, before, after));
        });
        return new Proposal(path, parts);
    }

    private static Map<BlockPos, Kind> requiredParts(RhizomeShootPath.Path path) {
        Map<BlockPos, Kind> required = new LinkedHashMap<>();
        path.risingPath().forEach(pos -> add(required, pos, Kind.RHIZOME));
        path.thinStems().forEach(pos -> add(required, pos, Kind.THIN_STEM));
        add(required, path.shootBase(), Kind.SHOOT);
        path.observed().forEach((pos, state) -> {
            if (!pos.equals(path.root()) && !state.isAir() && RhizomePathRules.isAirLike(state))
                required.putIfAbsent(pos, Kind.DECORATION_CLEANUP);
        });
        return required;
    }

    private static void add(Map<BlockPos, Kind> parts, BlockPos pos, Kind kind) {
        if (parts.putIfAbsent(pos.immutable(), kind) != null)
            throw new IllegalArgumentException("Overlapping shoot structure parts");
    }
}
