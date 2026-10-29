package com.example.life_sanctuary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Bounded own-chunk generation. Natural moss has no individual record or growth ticker. */
public final class AttachedMossFeature extends Feature<AttachedMossFeature.Configuration> {
    public record Configuration(int chunkChance) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, 1_000_000).fieldOf("chunk_chance").forGetter(Configuration::chunkChance)
        ).apply(instance, Configuration::new));
        public Configuration {
            if (chunkChance < 1 || chunkChance > 1_000_000) throw new IllegalArgumentException("Invalid attached moss frequency");
        }
    }
    public static final TagKey<Biome> BIOMES = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "attached_moss_biomes"));
    public static final TagKey<Block> PREFERRED = BlockTags.create(
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "attached_moss_preferred_supports"));
    public AttachedMossFeature() { super(Configuration.CODEC); }

    /** Skip trunks, leaves and non-full plant cover; never search more than 64 blocks down. */
    private static OptionalInt ground(WorldGenLevel world, int x, int z) {
        int top = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        for (int depth = 0; depth < 64; depth++) {
            var pos = new BlockPos(x, top - depth, z);
            if (world.isOutsideBuildHeight(pos)) break;
            var state = world.getBlockState(pos);
            if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)
                    && !state.is(LifeSanctuaryMod.NATURAL_PLANT_EXCLUSION)
                    && state.isCollisionShapeFullBlock(world, pos)) return OptionalInt.of(pos.getY());
        }
        return OptionalInt.empty();
    }
    @Override public boolean place(FeaturePlaceContext<Configuration> context) {
        var world = context.level();
        if (!(world instanceof net.minecraft.server.level.WorldGenRegion)) return false;
        var chunk = new ChunkPos(context.origin());
        if (!AttachedMossNaturalPlan.eligibleChunk(chunk.x, chunk.z, world.getSeed())) return false;
        var random = context.random();
        if (random.nextInt(context.config().chunkChance()) != 0) return false;
        var block = LifeSanctuaryMod.ATTACHED_MOSS.get();
        var candidates = new ArrayList<AttachedMossNaturalPlan.Candidate>();
        // The one-block rim keeps both the empty cell and its backing inside this chunk.
        for (int x = chunk.getMinBlockX() + 1; x < chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ() + 1; z < chunk.getMaxBlockZ(); z++) {
                var ground = ground(world, x, z);
                if (ground.isEmpty()) continue;
                for (int dy = 1; dy <= AttachedMossRules.PREFERRED_HEIGHT; dy++) {
                    var pos = new BlockPos(x, ground.getAsInt() + dy, z);
                    if (world.isOutsideBuildHeight(pos)) break;
                    var state = world.getBlockState(pos);
                    if (state.is(block)) return false;
                    if (!state.isAir() || !world.getBiome(pos).is(BIOMES)) continue;
                    for (var side : AttachedMossBlock.SIDES) {
                        var backing = pos.relative(side); var support = world.getBlockState(backing);
                        if (support.is(LifeSanctuaryMod.NATURAL_PLANT_EXCLUSION) || !AttachedMossBlock.supports(world, pos, side)) continue;
                        candidates.add(new AttachedMossNaturalPlan.Candidate(pos, side, support.is(PREFERRED)));
                    }
                }
            }
        }
        var plan = AttachedMossNaturalPlan.plan(chunk.x, chunk.z, world.getSeed(), candidates, random);
        if (plan.isEmpty()) return false;
        var targets = new LinkedHashMap<BlockPos, BlockState>();
        for (var entry : plan.entrySet()) {
            var pos = entry.getKey();
            if (!world.getBlockState(pos).isAir() || !AttachedMossBlock.supports(world, pos, entry.getValue())) return false;
            targets.put(pos, block.defaultBlockState().setValue(AttachedMossBlock.property(entry.getValue()), true));
        }
        var applied = new LinkedHashMap<BlockPos, BlockState>();
        for (var entry : targets.entrySet()) {
            var pos = entry.getKey(); var target = entry.getValue();
            if (!world.getBlockState(pos).isAir() || !target.canSurvive(world, pos)
                    || !world.setBlock(pos, target, Block.UPDATE_CLIENTS)) {
                if (world.getBlockState(pos).equals(target)) applied.put(pos, target);
                for (var written : applied.entrySet()) if (world.getBlockState(written.getKey()).equals(written.getValue()))
                    world.setBlock(written.getKey(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                return false;
            }
            applied.put(pos, target);
        }
        return true;
    }
}
