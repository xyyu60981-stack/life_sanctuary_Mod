package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** 横向末端路径事务：整条路径通过后再转换，成功后一次提交对应长度预算。 */
public final class RhizomeGrowthPlacement {
    private RhizomeGrowthPlacement() {}
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** 单格兼容入口，使用与整条路径相同的白名单及事务。 */
    public static PlantStructureCommitter.Status place(ServerLevel level, PlantRhizomeSystem expected,
            String arm, Direction direction, BlockState before, BlockState after,
            Map<BlockPos, BlockState> dependencies, Predicate<BlockState> validAttachment,
            Predicate<BlockState> legalEnvironment) {
        if (expected.growth().isEmpty() || !expected.growth().get().allTips().containsKey(arm)) return rejected();
        BlockPos target = expected.growth().get().tip(arm).position().relative(direction);
        Map<BlockPos, BlockState> reads = copy(dependencies);
        if (reads.containsKey(target) && !reads.get(target).equals(before))
            throw new IllegalArgumentException("Conflicting target dependency");
        reads.put(target, before);
        return placePath(level, expected, arm, List.of(direction), Map.of(target, after), reads,
                validAttachment, legalEnvironment);
    }

    /**
     * directions 为已选的有序路径；replacements 恰好覆盖目标，dependencies 含所有原状态和原末端。
     * 物种回调必须只读；空气、水和苔藓可转化方块使用相同白名单，附加规则不能替代此白名单。
     */
    public static PlantStructureCommitter.Status placePath(ServerLevel level, PlantRhizomeSystem expected,
            String arm, List<Direction> directions, Map<BlockPos, BlockState> replacements,
            Map<BlockPos, BlockState> dependencies, Predicate<BlockState> validAttachment,
            Predicate<BlockState> legalEnvironment) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        directions = List.copyOf(directions);
        if (directions.isEmpty() || directions.size() > 10 || expected.growth().isEmpty()) return rejected();
        var tip = expected.growth().get().tip(arm);
        if (tip == null || directions.size() > tip.remaining()) return rejected();
        var systems = PlantRhizomeSystems.get(level);
        var individuals = PlantIndividuals.get(level);
        if (systems.isReadOnly() || individuals.isReadOnly() || systems.needsRecovery(expected.id())
                || !systems.find(expected.id()).filter(expected::equals).isPresent()) return rejected();
        Map<BlockPos, BlockState> reads = copy(dependencies);
        if (!reads.containsKey(tip.position())) throw new IllegalArgumentException("Missing attachment dependency");
        Map<BlockPos, RhizomeShootStructure.Part> parts = new LinkedHashMap<>();
        var next = expected;
        for (Direction direction : directions) {
            var advanced = next.extendTip(arm, direction).orElse(null);
            if (advanced == null) return rejected();
            BlockPos target = advanced.growth().orElseThrow().tip(arm).position();
            var before = reads.get(target);
            var after = replacements.get(target);
            if (before == null || after == null) throw new IllegalArgumentException("Incomplete horizontal path snapshot");
            if (!RhizomeOverwrite.canConvert(systems, expected, target, before)) return rejected();
            parts.put(target, new RhizomeShootStructure.Part(RhizomeShootStructure.Kind.RHIZOME, before, after));
            next = advanced;
        }
        if (!parts.keySet().equals(replacements.keySet())) throw new IllegalArgumentException("Unrelated horizontal replacement");
        var newPoints = RhizomeGrowthPoints.active(next);
        parts.replaceAll((pos, part) -> new RhizomeShootStructure.Part(part.kind(), part.before(),
                RhizomeGrowthPoints.mark(part.after(), newPoints.contains(pos))));
        if (expected.rhizomes().contains(tip.position()) && !newPoints.contains(tip.position())) {
            var oldTip = reads.get(tip.position());
            var cleared = RhizomeGrowthPoints.mark(oldTip, false);
            if (!oldTip.equals(cleared)) parts.put(tip.position(), new RhizomeShootStructure.Part(
                    RhizomeShootStructure.Kind.GROWTH_POINT_UPDATE, oldTip, cleared));
        }
        // 完整可用性检查先于世界读取；任何一格不通过都不开始改块。
        for (var pos : reads.keySet()) if (!available(level, pos)) return rejected();
        Map<BlockPos, BlockState> overwritten = new LinkedHashMap<>();
        parts.forEach((pos, part) -> overwritten.put(pos, part.before()));
        var partners = RhizomeDecorationCleanup.partners(level, overwritten);
        if (partners.isEmpty()) return rejected();
        for (var entry : partners.get().entrySet()) {
            var old = reads.putIfAbsent(entry.getKey(), entry.getValue());
            if (old != null && !old.equals(entry.getValue())) return rejected();
            parts.putIfAbsent(entry.getKey(), new RhizomeShootStructure.Part(
                    RhizomeShootStructure.Kind.DECORATION_CLEANUP, entry.getValue(),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()));
        }
        if (!matches(level, reads, parts, false) || !validAttachment.test(reads.get(tip.position()))
                || !RhizomeOverwrite.ownsAttachment(level, expected, tip.position())) return rejected();
        if (expected.rhizomes().contains(tip.position()) && (!reads.get(tip.position()).hasProperty(RhizomeBlock.GROWTH_POINT)
                || !reads.get(tip.position()).getValue(RhizomeBlock.GROWTH_POINT))) return rejected();
        for (var entry : parts.entrySet()) {
            BlockPos pos = entry.getKey();
            if (entry.getValue().kind() == RhizomeShootStructure.Kind.GROWTH_POINT_UPDATE) {
                if (!systems.atRhizome(pos).filter(expected::equals).isPresent()
                        || individuals.atPart(pos).isPresent() || individuals.atBase(pos).isPresent()) return rejected();
                continue;
            }
            if (individuals.atPart(pos).isPresent() || individuals.atBase(pos).isPresent()) return rejected();
            if (entry.getValue().kind() == RhizomeShootStructure.Kind.RHIZOME) {
                if (!legalEnvironment.test(entry.getValue().before())
                        || !RhizomeOverwrite.canConvert(systems, expected, pos, entry.getValue().before())) return rejected();
            } else if (systems.atRhizome(pos).isPresent() || systems.atOrigin(pos).isPresent()
                    || systems.atShootBase(pos).isPresent() || systems.atAttachment(pos).isPresent()) return rejected();
        }
        var transfers = RhizomeOverwrite.transfers(systems, expected, parts);
        final var proposed = next;
        boolean lockedSystems = false, lockedIndividuals = false, committed = false, restored = true;
        List<BlockPos> attempted = new ArrayList<>();
        try {
            systems.beginHorizontalStructure(expected, proposed, arm, directions, transfers);
            lockedSystems = true;
            individuals.beginRhizomeStructure(expected.id());
            lockedIndividuals = true;
            if (!matches(level, reads, parts, false)) throw new IllegalStateException("Rhizome path changed");
            for (var entry : parts.entrySet()) {
                BlockPos pos = entry.getKey();
                if (!available(level, pos) || !level.getBlockState(pos).equals(entry.getValue().before()))
                    throw new IllegalStateException("Rhizome target changed");
                if (entry.getValue().before().equals(entry.getValue().after())) continue;
                attempted.add(pos);
                if (!level.setBlock(pos, entry.getValue().after(), FLAGS)) throw new IllegalStateException("Rhizome write failed");
            }
            if (!matches(level, reads, parts, true)) throw new IllegalStateException("Rhizome callback changed path");
            systems.commitShootStructure(expected, proposed);
            committed = true;
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(RhizomeGrowthPlacement.class).error("Rhizome path placement failed", failure);
            for (int i = attempted.size() - 1; i >= 0; i--) {
                BlockPos pos = attempted.get(i);
                var part = parts.get(pos);
                try {
                    if (!available(level, pos)) { restored = false; continue; }
                    if (level.getBlockState(pos).equals(part.after())) level.setBlock(pos, part.before(), FLAGS);
                } catch (RuntimeException recoveryFailure) {
                    restored = false;
                    org.slf4j.LoggerFactory.getLogger(RhizomeGrowthPlacement.class).error("Cannot restore rhizome at " + pos, recoveryFailure);
                }
            }
            restored &= matches(level, reads, parts, false);
            if (!restored) {
                // 保留整个拟路径占位用于诊断，异常停机期间不允许重试或刷新预算。
                if (lockedSystems && lockedIndividuals) {
                    try {
                        systems.commitShootStructure(expected, proposed);
                    } catch (RuntimeException recoveryFailure) {
                        org.slf4j.LoggerFactory.getLogger(RhizomeGrowthPlacement.class).error("Cannot retain rhizome recovery metadata", recoveryFailure);
                    }
                }
                RhizomeOverwrite.requireRecovery(systems, expected.id(), transfers);
            }
        } finally {
            if (lockedIndividuals) individuals.endStructure();
            if (lockedSystems) systems.endShootStructure();
        }
        if (!committed) return restored ? PlantStructureCommitter.Status.ROLLED_BACK
                : PlantStructureCommitter.Status.RECOVERY_REQUIRED;
        try {
            for (var entry : parts.entrySet()) {
                if (entry.getValue().before().equals(entry.getValue().after())) continue;
                if (!available(level, entry.getKey())) throw new IllegalStateException("Rhizome chunk became unavailable");
                var after = entry.getValue().after();
                after.updateNeighbourShapes(level, entry.getKey(), Block.UPDATE_ALL);
                level.updateNeighborsAt(entry.getKey(), after.getBlock());
            }
            if (!matches(level, reads, parts, true) || !systems.find(proposed.id()).filter(proposed::equals).isPresent()
                    || transfers.values().stream().anyMatch(transfer -> !systems.find(transfer.after().id()).filter(transfer.after()::equals).isPresent())) {
                RhizomeOverwrite.requireRecovery(systems, expected.id(), transfers);
                return PlantStructureCommitter.Status.RECOVERY_REQUIRED;
            }
        } catch (RuntimeException failure) {
            RhizomeOverwrite.requireRecovery(systems, expected.id(), transfers);
            org.slf4j.LoggerFactory.getLogger(RhizomeGrowthPlacement.class).error("Rhizome path neighbor update failed", failure);
            return PlantStructureCommitter.Status.RECOVERY_REQUIRED;
        }
        return PlantStructureCommitter.Status.COMMITTED;
    }

    private static boolean matches(ServerLevel level, Map<BlockPos, BlockState> reads,
            Map<BlockPos, RhizomeShootStructure.Part> parts, boolean after) {
        try {
            for (var entry : reads.entrySet()) {
                var part = parts.get(entry.getKey());
                var state = after && part != null ? part.after() : entry.getValue();
                if (!available(level, entry.getKey()) || !level.getBlockState(entry.getKey()).equals(state)) return false;
            }
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Map<BlockPos, BlockState> copy(Map<BlockPos, BlockState> source) {
        Map<BlockPos, BlockState> copy = new LinkedHashMap<>();
        source.forEach((pos, state) -> copy.put(pos.immutable(), java.util.Objects.requireNonNull(state)));
        return copy;
    }

    private static boolean available(ServerLevel level, BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.getChunkSource().hasChunk(x, z)
                && level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z));
    }

    private static PlantStructureCommitter.Status rejected() { return PlantStructureCommitter.Status.REJECTED; }
}
