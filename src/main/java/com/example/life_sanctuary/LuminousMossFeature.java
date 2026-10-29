package com.example.life_sanctuary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Own-chunk one-shot patch. Never access SavedData or force a neighbor chunk on a worldgen thread. */
public final class LuminousMossFeature extends Feature<LuminousMossFeature.Configuration> {
    public record Configuration(int chunkChance, double capsuleChance) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, 1_000_000).optionalFieldOf("chunk_chance", 4).forGetter(Configuration::chunkChance),
                Codec.doubleRange(0, 1).optionalFieldOf("capsule_chance", 0.25).forGetter(Configuration::capsuleChance)
        ).apply(instance, Configuration::new));
        public Configuration {
            if (chunkChance < 1 || chunkChance > 1_000_000 || !Double.isFinite(capsuleChance)
                    || capsuleChance < 0 || capsuleChance > 1) throw new IllegalArgumentException("Invalid luminous moss generation probability");
        }
        public boolean tryChunk(net.minecraft.util.RandomSource random) { return random.nextInt(chunkChance) == 0; }
        public int initialAge(net.minecraft.util.RandomSource random) { return random.nextDouble() < capsuleChance ? 2 : 1; }
    }
    public static final TagKey<Biome> BIOMES = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "luminous_moss_biomes"));
    public LuminousMossFeature() { super(Configuration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<Configuration> context) {
        var world = context.level();
        if (!(world instanceof net.minecraft.server.level.WorldGenRegion)) return false;
        var region = new ChunkPos(context.origin()); var random = context.random();
        if (!context.config().tryChunk(random)) return false;
        var block = LifeSanctuaryMod.LUMINOUS_MOSS.get();
        var candidates = new ArrayList<BlockPos>(); int existing = 0;
        for (int x = region.getMinBlockX(); x <= region.getMaxBlockX(); x++) {
            for (int z = region.getMinBlockZ(); z <= region.getMaxBlockZ(); z++) {
                var pos = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (world.isOutsideBuildHeight(pos)) continue;
                var state = world.getBlockState(pos);
                if (state.is(block)) { existing++; continue; }
                if (state.isAir() && world.getBiome(pos).is(BIOMES) && block.defaultBlockState().canSurvive(world, pos)
                        && !world.getBlockState(pos.below()).is(LifeSanctuaryMod.NATURAL_PLANT_EXCLUSION)) candidates.add(pos);
            }
        }
        if (existing != 0 || candidates.size() < LuminousMossRules.MINIMUM_NATURAL) return false;
        int count = Math.min(LuminousMossRules.naturalCount(random), candidates.size());
        for (int i = candidates.size() - 1; i > 0; i--)
            java.util.Collections.swap(candidates, i, random.nextInt(i + 1));
        var applied = new LinkedHashMap<BlockPos, BlockState>();
        for (int i = 0; i < count; i++) {
            var pos = candidates.get(i);
            // The two capsule planes are one structure: one per-plant 25% roll, never one roll per plane.
            var target = block.defaultBlockState().setValue(LuminousMossBlock.AGE, context.config().initialAge(random));
            if (!world.getBlockState(pos).isAir() || !target.canSurvive(world, pos)
                    || !world.setBlock(pos, target, Block.UPDATE_CLIENTS)) {
                if (world.getBlockState(pos).equals(target))
                    world.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                for (var entry : applied.entrySet()) if (world.getBlockState(entry.getKey()).equals(entry.getValue()))
                    world.setBlock(entry.getKey(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                return false;
            }
            applied.put(pos, target);
        }
        applied.keySet().forEach(pos -> world.scheduleTick(pos, block, 1));
        return true;
    }
}
