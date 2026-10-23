package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only shared checks. Does not place blocks, consume quotas or advance a lifecycle. */
public final class PlantStructurePreflight {
    public enum Reason {
        READY, READ_ONLY, STALE_INDIVIDUAL, OUTSIDE_BUILD_HEIGHT, OUTSIDE_WORLD_BORDER,
        CHUNK_NOT_TICKING, WORLD_CHANGED, OTHER_INDIVIDUAL, BLOCK_ENTITY, OCCUPIED, NOT_OWNED,
        FLUID_UNSUPPORTED, OUTSIDE_POPULATION, SYSTEM_RECOVERY_REQUIRED
    }
    public record Result(Reason reason, Optional<BlockPos> position) {
        public boolean ready() { return reason == Reason.READY; }
    }
    private PlantStructurePreflight() {}
    private static Result result(Reason reason, BlockPos pos) {
        return new Result(reason, Optional.ofNullable(pos).map(BlockPos::immutable));
    }

    public static Result check(ServerLevel level, PlantIndividuals store, PlantStructurePlan plan) {
        if (store.isReadOnly()) return result(Reason.READ_ONLY, null);
        var individual = plan.expectedIndividual();
        if (!individual.equals(store.find(individual.id()).orElse(null)))
            return result(Reason.STALE_INDIVIDUAL, individual.base());
        var systems = PlantRhizomeSystems.get(level);
        var shared = systems.forShoot(individual.id()).orElseGet(() -> systems.find(individual.id()).orElse(null));
        if (systems.isReadOnly() && RhizomePopulation.findStages(individual.species()).isPresent()) return result(Reason.READ_ONLY, individual.base());
        if (shared != null && systems.needsRecovery(shared.id())) return result(Reason.SYSTEM_RECOVERY_REQUIRED, individual.base());
        if (shared != null || RhizomePopulation.findStages(individual.species()).isPresent()) {
            BlockPos origin = shared == null ? individual.base() : shared.origin();
            for (var entry : plan.changes().entrySet()) {
                if (entry.getValue().role().isEmpty()) continue;
                if (Math.floorDiv(entry.getKey().getX(), 32) != Math.floorDiv(origin.getX(), 32)
                        || Math.floorDiv(entry.getKey().getZ(), 32) != Math.floorDiv(origin.getZ(), 32))
                    return result(Reason.OUTSIDE_POPULATION, entry.getKey());
            }
        }
        Map<BlockPos, BlockState> expected = new LinkedHashMap<>(plan.dependencies());
        plan.changes().forEach((pos, change) -> expected.put(pos, change.expected()));

        // Complete availability pass precedes every world block read, including support dependencies.
        var requiredPositions = new java.util.LinkedHashSet<>(expected.keySet());
        requiredPositions.add(individual.base());
        for (BlockPos pos : requiredPositions) {
            if (level.isOutsideBuildHeight(pos)) return result(Reason.OUTSIDE_BUILD_HEIGHT, pos);
            if (!level.getWorldBorder().isWithinBounds(pos)) return result(Reason.OUTSIDE_WORLD_BORDER, pos);
            long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                    || !level.getChunkSource().isPositionTicking(chunk))
                return result(Reason.CHUNK_NOT_TICKING, pos);
        }
        for (var entry : expected.entrySet())
            if (!level.getBlockState(entry.getKey()).equals(entry.getValue()))
                return result(Reason.WORLD_CHANGED, entry.getKey());

        for (var entry : plan.changes().entrySet()) {
            BlockPos pos = entry.getKey();
            var change = entry.getValue();
            var partOwner = store.atPart(pos);
            var anchorOwner = store.atBase(pos);
            if (partOwner.filter(owner -> !owner.id().equals(individual.id())).isPresent()
                    || anchorOwner.filter(owner -> !owner.id().equals(individual.id())).isPresent())
                return result(Reason.OTHER_INDIVIDUAL, pos);
            if (systems.atRhizome(pos).isPresent() && !change.policy().requiresRhizomeOwnership())
                return result(Reason.NOT_OWNED, pos);
            var replacement = checkReplacement(level, pos, change, partOwner.isPresent(),
                    shared != null && systems.atRhizome(pos).filter(owner -> owner.id().equals(shared.id())).isPresent());
            if (!replacement.ready()) return replacement;
        }
        return result(Reason.READY, null);
    }

    /** 替换策略的共享校验；调用者须先完成可用区块、原状态与身份检查。 */
    static Result checkReplacement(ServerLevel level, BlockPos pos, PlantStructurePlan.Change change,
            boolean ownedPart, boolean ownedRhizome) {
        // Generic growth must not discard inventories or block-entity data.
        if (change.expected().hasBlockEntity() || change.replacement().hasBlockEntity())
            return result(Reason.BLOCK_ENTITY, pos);
        // Dry policies retain their original safety boundary. Wet policies preserve source/flow state exactly.
        if (change.policy().preservesWater()) {
            if (!change.expected().getFluidState().is(FluidTags.WATER)
                    || !change.expected().getFluidState().equals(change.replacement().getFluidState()))
                return result(Reason.FLUID_UNSUPPORTED, pos);
        } else if (!change.expected().getFluidState().isEmpty() || !change.replacement().getFluidState().isEmpty())
            return result(Reason.FLUID_UNSUPPORTED, pos);
        switch (change.policy()) {
            case AIR_ONLY -> {
                if (!change.expected().isAir()) return result(Reason.OCCUPIED, pos);
            }
            case REPLACEABLE_DRY -> {
                if (!change.expected().canBeReplaced() || !change.expected().getFluidState().isEmpty())
                    return result(Reason.OCCUPIED, pos);
            }
            case DECORATION_ONLY -> {
                if (!RhizomePathRules.isAirLike(change.expected())) return result(Reason.OCCUPIED, pos);
            }
            case MOSS_SUBSTRATE, MOSS_SUBSTRATE_WATER -> {
                float hardness = change.expected().getDestroySpeed(level, pos);
                if ((!change.expected().is(Blocks.MOSS_BLOCK) && !change.expected().is(BlockTags.MOSS_REPLACEABLE))
                        || hardness < 0 || hardness > Blocks.DEEPSLATE.defaultBlockState().getDestroySpeed(level, pos))
                    return result(Reason.OCCUPIED, pos);
            }
            case OWNED_RHIZOME, OWNED_WATER_RHIZOME -> {
                if (!ownedRhizome
                        || !(change.expected().getBlock() instanceof RhizomeBlock)) return result(Reason.NOT_OWNED, pos);
            }
            case WATER_PRESERVING -> {
                // Do not overwrite an unrelated waterlogged block merely because it contains water.
                if (!change.expected().is(Blocks.WATER)) return result(Reason.OCCUPIED, pos);
            }
            case OWNED_PART, OWNED_WATER_PART -> {
                if (!ownedPart) return result(Reason.NOT_OWNED, pos);
            }
        }
        return result(Reason.READY, null);
    }
}
