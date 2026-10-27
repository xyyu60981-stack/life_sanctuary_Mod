package com.example.life_sanctuary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Register the feature type now; biome authors activate it only with real materials and lake placement. */
public final class LavaLakeRemnantFeature extends Feature<LavaLakeRemnantFeature.Configuration> {
    public record Material(BlockState state, int weight) {
        public static final Codec<Material> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(Material::state), Codec.intRange(1, 1000).fieldOf("weight").forGetter(Material::weight)
        ).apply(instance, Material::new));
        public Material {
            if (weight < 1 || weight > 1000 || !(state.getBlock() instanceof CharredMaterialBlock || state.getBlock() instanceof BurnedMaterialBlock)
                    || state.getBlock() instanceof CharredMaterialBlock && state.getValue(CharredMaterialBlock.LIT))
                throw new IllegalArgumentException("Lake remnants require actual unlit charred or burned material");
        }
    }
    public record Configuration(TagKey<Biome> biomes, TagKey<Block> substrates, List<Material> materials,
            int horizontalRadius, int verticalRadius, int lavaRadius, int minimumLavaSources, int attempts, int maximumPlacements) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TagKey.codec(Registries.BIOME).fieldOf("biomes").forGetter(Configuration::biomes),
                TagKey.codec(Registries.BLOCK).fieldOf("substrates").forGetter(Configuration::substrates),
                Material.CODEC.listOf().fieldOf("materials").forGetter(Configuration::materials),
                Codec.intRange(1, 16).fieldOf("horizontal_radius").forGetter(Configuration::horizontalRadius),
                Codec.intRange(0, 8).fieldOf("vertical_radius").forGetter(Configuration::verticalRadius),
                Codec.intRange(1, 4).fieldOf("lava_radius").forGetter(Configuration::lavaRadius),
                Codec.intRange(1, 160).fieldOf("minimum_lava_sources").forGetter(Configuration::minimumLavaSources),
                Codec.intRange(1, 16).fieldOf("attempts").forGetter(Configuration::attempts),
                Codec.intRange(1, 4).fieldOf("maximum_placements").forGetter(Configuration::maximumPlacements)
        ).apply(instance, Configuration::new));
        public Configuration {
            java.util.Objects.requireNonNull(biomes); java.util.Objects.requireNonNull(substrates);
            materials = List.copyOf(materials);
            if (materials.isEmpty() || materials.size() > 16) throw new IllegalArgumentException("Invalid remnant palette");
            new LavaLakeRemnantRules.Limits(horizontalRadius, verticalRadius, lavaRadius, minimumLavaSources, attempts, maximumPlacements);
        }
        LavaLakeRemnantRules.Limits limits() {
            return new LavaLakeRemnantRules.Limits(horizontalRadius, verticalRadius, lavaRadius, minimumLavaSources, attempts, maximumPlacements);
        }
    }
    public LavaLakeRemnantFeature() { super(Configuration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<Configuration> context) {
        var level = context.level(); var origin = context.origin(); var config = context.config(); var random = context.random();
        var cells = new HashMap<BlockPos, LavaLakeRemnantRules.Cell>();
        var snapshot = new LavaLakeRemnantRules.Snapshot() {
            @Override public boolean allowedBiome(BlockPos pos) {
                if (level.isOutsideBuildHeight(pos) || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
                var biome = level.getBiome(pos);
                return biome.is(config.biomes()) && biome.unwrapKey().filter(key -> key.location().getNamespace().equals("life_sanctuary")).isPresent();
            }
            @Override public LavaLakeRemnantRules.Cell cell(BlockPos pos) {
                return cells.computeIfAbsent(pos.immutable(), key -> {
                    if (level.isOutsideBuildHeight(key) || !level.hasChunk(key.getX() >> 4, key.getZ() >> 4)) return LavaLakeRemnantRules.Cell.UNKNOWN;
                    var state = level.getBlockState(key); var fluid = state.getFluidState();
                    return new LavaLakeRemnantRules.Cell(true, state.isAir(), fluid.isEmpty(), state.hasBlockEntity(),
                            !state.hasBlockEntity() && state.is(config.substrates()) && state.isFaceSturdy(level, key, Direction.UP), fluid.is(FluidTags.LAVA) && fluid.isSource());
                });
            }
        };
        if (!snapshot.allowedBiome(origin)) return false;
        var seen = new HashSet<BlockPos>(); int placed = 0;
        for (int attempt = 0; attempt < config.attempts() && placed < config.maximumPlacements(); attempt++) {
            var pos = origin.offset(random.nextInt(config.horizontalRadius() * 2 + 1) - config.horizontalRadius(),
                    random.nextInt(config.verticalRadius() * 2 + 1) - config.verticalRadius(),
                    random.nextInt(config.horizontalRadius() * 2 + 1) - config.horizontalRadius());
            if ((pos.getX() >> 4) != (origin.getX() >> 4) || (pos.getZ() >> 4) != (origin.getZ() >> 4) || !seen.add(pos)
                    || level.isOutsideBuildHeight(pos) || !level.ensureCanWrite(pos)
                    || LavaLakeRemnantRules.inspect(pos, config.limits(), snapshot) != LavaLakeRemnantRules.Result.READY) continue;
            int pick = random.nextInt(config.materials().stream().mapToInt(Material::weight).sum());
            BlockState chosen = null;
            for (var material : config.materials()) { pick -= material.weight(); if (pick < 0) { chosen = material.state(); break; } }
            if (chosen != null && level.getBlockState(pos).isAir() && level.getFluidState(pos).isEmpty()
                    && level.setBlock(pos, chosen, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                placed++; cells.remove(pos);
                // Persist a one-shot block tick; actual SavedData initialization happens later on the server thread.
                if (chosen.getBlock() instanceof CharredMaterialBlock) level.scheduleTick(pos, chosen.getBlock(), 1);
            }
        }
        return placed > 0;
    }
}
