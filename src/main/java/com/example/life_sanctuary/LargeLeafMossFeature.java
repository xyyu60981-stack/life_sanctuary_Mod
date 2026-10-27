package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/** Explicitly invoked worldgen patch: own-chunk surface only, no SavedData access on worldgen threads. */
public final class LargeLeafMossFeature extends Feature<NoneFeatureConfiguration> {
    public LargeLeafMossFeature() { super(NoneFeatureConfiguration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        var world = context.level(); var region = new ChunkPos(context.origin()); var random = context.random();
        if (!(world instanceof net.minecraft.server.level.WorldGenRegion)) return false;
        var candidates = new ArrayList<BlockPos>(); int existing = 0;
        for (int x = region.getMinBlockX(); x <= region.getMaxBlockX(); x++) for (int z = region.getMinBlockZ(); z <= region.getMaxBlockZ(); z++) {
            var pos = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (world.isOutsideBuildHeight(pos)) continue;
            var state = world.getBlockState(pos);
            if (state.getBlock() instanceof LargeLeafMossBlock) { existing++; continue; }
            var support = world.getBlockState(pos.below());
            if (state.isAir() && support.is(BlockTags.DIRT) && world.getBiome(pos).is(LargeLeafMossNatural.BIOMES)
                    && !support.is(LifeSanctuaryMod.NATURAL_PLANT_EXCLUSION)) candidates.add(pos);
        }
        int count = Math.min(Math.min(4 + random.nextInt(5), LargeLeafMossRules.LIMIT - existing), candidates.size());
        if (count < 4) return false;
        for (int i = candidates.size() - 1; i > 0; i--) java.util.Collections.swap(candidates, i, random.nextInt(i + 1));
        var changes = new LinkedHashMap<BlockPos, BlockState>();
        var block = LifeSanctuaryMod.LARGE_LEAF_MOSS.get();
        for (int i = 0; i < count; i++) {
            var pos = candidates.get(i);
            var state = block.defaultBlockState().setValue(LargeLeafMossBlock.COUNT, LargeLeafMossRules.naturalDensity(random.nextInt(100)))
                    .setValue(LargeLeafMossBlock.CURLED, world.getBiome(pos).is(LifeSanctuaryMod.DRY_BIOMES));
            changes.put(pos, state);
        }
        var applied = new ArrayList<BlockPos>();
        for (var entry : changes.entrySet()) {
            if (!world.getBlockState(entry.getKey()).isAir() || !world.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_CLIENTS)) {
                for (var pos : applied) if (world.getBlockState(pos).equals(changes.get(pos))) world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                return false;
            }
            applied.add(entry.getKey());
        }
        // Initialize real static identities later on the loaded server thread; never wake natural growth.
        changes.keySet().forEach(pos -> world.scheduleTick(pos, block, 1));
        return true;
    }
}
