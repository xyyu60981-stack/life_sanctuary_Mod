package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** 自然芦木整组只读预检；不临时登记个体，不加载区块，不消耗一次生成凭据。 */
public final class RhizomeNaturalPreflight {
    private RhizomeNaturalPreflight() {}
    public enum Reason {
        READY, READ_ONLY, APPLYING_STRUCTURE, ID_CONFLICT, OWNERSHIP_CONFLICT,
        OUTSIDE_BUILD_HEIGHT, OUTSIDE_WORLD_BORDER, CHUNK_NOT_TICKING,
        WORLD_CHANGED, REPLACEMENT_REJECTED
    }
    public record Result(Reason reason, Optional<BlockPos> position,
            Optional<PlantStructurePreflight.Reason> replacementReason, Optional<Checked> checked) {
        public boolean ready() { return reason == Reason.READY; }
    }
    /** 瞬时读取结果，不是提交授权；未来提交器仍须复核存储与全部快照。 */
    public record Checked(RhizomeNaturalStructurePlan plan, Map<BlockPos, BlockState> expectedStates,
            Map<BlockPos, BlockState> decorationCleanup) {
        public Checked {
            expectedStates = Map.copyOf(expectedStates);
            decorationCleanup = Map.copyOf(decorationCleanup);
        }
    }

    public static Result check(ServerLevel level, RhizomeNaturalStructurePlan plan) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        if (individuals.isReadOnly() || systems.isReadOnly()) return rejected(Reason.READ_ONLY, null);
        if (individuals.isApplyingStructure() || systems.isApplyingStructure())
            return rejected(Reason.APPLYING_STRUCTURE, null);
        for (var plant : plan.population().plants().values()) {
            if (individuals.find(plant.id()).isPresent() || systems.find(plant.id()).isPresent()
                    || systems.forShoot(plant.id()).isPresent()) return rejected(Reason.ID_CONFLICT, plant.base());
        }

        Map<BlockPos, BlockState> reads = new HashMap<>(plan.expectedStates());
        var writes = new HashSet<>(plan.underground().keySet());
        plan.plants().values().forEach(plant -> writes.addAll(plant.changes().keySet()));
        // 双格装饰另一半的坐标从原快照推导，先加入全量可用性检查，再读任何方块。
        var partners = new HashSet<BlockPos>();
        for (var pos : writes) {
            var before = reads.get(pos);
            if (RhizomePathRules.isAirLike(before) && before.getBlock() instanceof DoublePlantBlock)
                partners.add(before.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below());
        }
        var required = new HashSet<>(reads.keySet());
        required.addAll(partners);
        var checkedChunks = new HashSet<Long>();
        for (var pos : required) {
            if (level.isOutsideBuildHeight(pos)) return rejected(Reason.OUTSIDE_BUILD_HEIGHT, pos);
            if (!level.getWorldBorder().isWithinBounds(pos)) return rejected(Reason.OUTSIDE_WORLD_BORDER, pos);
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            long chunk = ChunkPos.asLong(x, z);
            if (checkedChunks.add(chunk) && (!level.getChunkSource().hasChunk(x, z)
                    || !level.getChunkSource().isPositionTicking(chunk)))
                return rejected(Reason.CHUNK_NOT_TICKING, pos);
        }
        for (var entry : reads.entrySet())
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue()))
                return rejected(Reason.WORLD_CHANGED, entry.getKey());
        // 即使另一半已经不再匹配，也保留读取依赖，防止提交前它又被改回。
        for (var pos : partners) reads.putIfAbsent(pos, level.getBlockState(pos));
        for (var pos : writes)
            if (occupied(individuals, systems, pos)) return rejected(Reason.OWNERSHIP_CONFLICT, pos);

        for (var plant : plan.plants().values()) for (var entry : plant.changes().entrySet()) {
            var checked = PlantStructurePreflight.checkReplacement(level, entry.getKey(), entry.getValue(), false, false);
            if (!checked.ready()) return new Result(Reason.REPLACEMENT_REJECTED, checked.position(),
                    Optional.of(checked.reason()), Optional.empty());
        }
        for (var entry : plan.underground().entrySet()) {
            // 标签可能在计划生成后重载，不能只信任构造时的白名单判定。
            if (!RhizomePathRules.canConvert(entry.getValue().expected()))
                return new Result(Reason.REPLACEMENT_REJECTED, Optional.of(entry.getKey()),
                        Optional.of(PlantStructurePreflight.Reason.OCCUPIED), Optional.empty());
        }

        Map<BlockPos, BlockState> cleanup = new HashMap<>();
        for (var pos : writes) {
            var before = reads.get(pos);
            if (!RhizomePathRules.isAirLike(before) || !(before.getBlock() instanceof DoublePlantBlock)) continue;
            var half = before.getValue(DoublePlantBlock.HALF);
            var partnerPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            var partner = reads.get(partnerPos);
            if (!partner.is(before.getBlock()) || partner.getValue(DoublePlantBlock.HALF) == half) continue;
            if (writes.contains(partnerPos)) continue; // 已在整组写入中，不能再清空它。
            if (occupied(individuals, systems, partnerPos)) return rejected(Reason.OWNERSHIP_CONFLICT, partnerPos);
            if (!RhizomePathRules.isAirLike(partner)) return rejected(Reason.REPLACEMENT_REJECTED, partnerPos);
            cleanup.put(partnerPos, partner);
        }
        // 硬度等方块方法可能来自其他模组；回调后再次确认，不返回已经过期的成功快照。
        if (individuals.isReadOnly() || systems.isReadOnly()) return rejected(Reason.READ_ONLY, null);
        if (individuals.isApplyingStructure() || systems.isApplyingStructure())
            return rejected(Reason.APPLYING_STRUCTURE, null);
        for (var plant : plan.population().plants().values())
            if (individuals.find(plant.id()).isPresent() || systems.find(plant.id()).isPresent()
                    || systems.forShoot(plant.id()).isPresent()) return rejected(Reason.ID_CONFLICT, plant.base());
        for (var pos : writes)
            if (occupied(individuals, systems, pos)) return rejected(Reason.OWNERSHIP_CONFLICT, pos);
        for (var pos : cleanup.keySet())
            if (occupied(individuals, systems, pos)) return rejected(Reason.OWNERSHIP_CONFLICT, pos);
        checkedChunks.clear();
        for (var entry : reads.entrySet()) {
            var pos = entry.getKey();
            if (!level.getWorldBorder().isWithinBounds(pos)) return rejected(Reason.OUTSIDE_WORLD_BORDER, pos);
            long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (checkedChunks.add(chunk) && (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || !level.getChunkSource().isPositionTicking(chunk)))
                return rejected(Reason.CHUNK_NOT_TICKING, pos);
            if (!level.getBlockState(pos).equals(entry.getValue())) return rejected(Reason.WORLD_CHANGED, pos);
        }
        return new Result(Reason.READY, Optional.empty(), Optional.empty(),
                Optional.of(new Checked(plan, reads, cleanup)));
    }

    private static boolean occupied(PlantIndividuals individuals, PlantRhizomeSystems systems, BlockPos pos) {
        return individuals.atPart(pos).isPresent() || individuals.atBase(pos).isPresent()
                || systems.atRhizome(pos).isPresent() || systems.atOrigin(pos).isPresent()
                || systems.atAttachment(pos).isPresent() || systems.atShootBase(pos).isPresent();
    }

    private static Result rejected(Reason reason, BlockPos pos) {
        return new Result(reason, Optional.ofNullable(pos).map(BlockPos::immutable), Optional.empty(), Optional.empty());
    }
}
