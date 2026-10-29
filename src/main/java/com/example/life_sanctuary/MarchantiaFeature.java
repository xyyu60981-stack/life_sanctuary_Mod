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

/** One region, one complete patch. No SavedData reads or neighboring chunk requests during worldgen. */
public final class MarchantiaFeature extends Feature<MarchantiaFeature.Configuration> {
    public record Configuration(int chunkChance, double reproductiveChance) implements FeatureConfiguration {
        // Required fields deliberately have no guessed species defaults.
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, 1_000_000).fieldOf("chunk_chance").forGetter(Configuration::chunkChance),
                Codec.doubleRange(0, 1).fieldOf("reproductive_chance").forGetter(Configuration::reproductiveChance)
        ).apply(instance, Configuration::new));
        public Configuration {
            if (chunkChance < 1 || chunkChance > 1_000_000 || !Double.isFinite(reproductiveChance)
                    || reproductiveChance < 0 || reproductiveChance > 1) throw new IllegalArgumentException("Invalid Marchantia generation probability");
        }
    }
    public static final TagKey<Biome> BIOMES = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "marchantia_biomes"));
    public MarchantiaFeature() { super(Configuration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<Configuration> context) {
        var world = context.level();
        if (!(world instanceof net.minecraft.server.level.WorldGenRegion)) return false;
        var random = context.random();
        if (random.nextInt(context.config().chunkChance()) != 0) return false;
        var region = new ChunkPos(context.origin());
        var block = LifeSanctuaryMod.MARCHANTIA.get();
        var candidates = new ArrayList<BlockPos>();
        for (int x = region.getMinBlockX(); x <= region.getMaxBlockX(); x++) {
            for (int z = region.getMinBlockZ(); z <= region.getMaxBlockZ(); z++) {
                var pos = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (world.isOutsideBuildHeight(pos) || world.isOutsideBuildHeight(pos.below())) continue;
                var state = world.getBlockState(pos);
                if (state.is(block)) return false;
                if (state.isAir() && world.getBiome(pos).is(BIOMES) && block.defaultBlockState().canSurvive(world, pos)
                        && !world.getBlockState(pos.below()).is(LifeSanctuaryMod.NATURAL_PLANT_EXCLUSION)) candidates.add(pos);
            }
        }
        var layout = MarchantiaNaturalLayout.plan(candidates, random);
        var positions = layout.positions();
        if (positions.isEmpty()) return false;
        var targets = new LinkedHashMap<BlockPos, BlockState>();
        for (var pos : positions) {
            boolean reproductive = random.nextDouble() < context.config().reproductiveChance();
            var target = block.defaultBlockState().setValue(MarchantiaBlock.SURFACE, MarchantiaBlock.surface(world.getBlockState(pos.below())))
                    .setValue(MarchantiaBlock.REPRODUCTIVE, reproductive)
                    .setValue(MarchantiaBlock.FEMALE_COUNT, reproductive ? 1 + random.nextInt(2) : 1);
            if (!world.getBlockState(pos).isAir() || !target.canSurvive(world, pos)) return false;
            targets.put(pos, target);
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
        // Identity registration happens later on the server thread, not on the worldgen worker.
        applied.keySet().forEach(pos -> world.scheduleTick(pos, block, 1));
        return true;
    }
}
