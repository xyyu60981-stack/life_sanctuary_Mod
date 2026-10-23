package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** 为被覆盖的双格装饰补齐另一半快照，供事务显式无掉落清理及回滚。 */
final class RhizomeDecorationCleanup {
    private RhizomeDecorationCleanup() {}

    static Optional<Map<BlockPos, BlockState>> partners(ServerLevel level, Map<BlockPos, BlockState> overwritten) {
        Map<BlockPos, BlockState> partners = new LinkedHashMap<>();
        var individuals = PlantIndividuals.get(level);
        var systems = PlantRhizomeSystems.get(level);
        for (var entry : overwritten.entrySet()) {
            var state = entry.getValue();
            if (!RhizomePathRules.isAirLike(state) || !(state.getBlock() instanceof DoublePlantBlock)) continue;
            var half = state.getValue(DoublePlantBlock.HALF);
            BlockPos pos = half == DoubleBlockHalf.LOWER ? entry.getKey().above() : entry.getKey().below();
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getChunkSource().hasChunk(x, z)
                    || !level.getChunkSource().isPositionTicking(ChunkPos.asLong(x, z))) return Optional.empty();
            var partner = level.getBlockState(pos);
            if (!partner.is(state.getBlock()) || partner.getValue(DoublePlantBlock.HALF) == half) continue;
            if (!RhizomePathRules.isAirLike(partner) || individuals.atPart(pos).isPresent()
                    || individuals.atBase(pos).isPresent() || systems.atRhizome(pos).isPresent()
                    || systems.atOrigin(pos).isPresent() || systems.atShootBase(pos).isPresent()
                    || systems.atAttachment(pos).isPresent()) return Optional.empty();
            var old = overwritten.get(pos);
            if (old != null && !old.equals(partner)) return Optional.empty();
            partners.put(pos.immutable(), partner);
        }
        return Optional.of(Map.copyOf(partners));
    }
}
