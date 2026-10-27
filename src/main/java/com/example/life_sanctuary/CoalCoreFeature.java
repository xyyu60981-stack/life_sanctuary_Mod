package com.example.life_sanctuary;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Rare scattered remnants and authored fossils share a bounded, all-parts preflight. */
public final class CoalCoreFeature extends Feature<CoalCoreFeature.Configuration> {
    public record Material(BlockState stone, Optional<BlockState> deepslate) {
        public static final Codec<Material> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockState.CODEC.fieldOf("stone").forGetter(Material::stone), BlockState.CODEC.optionalFieldOf("deepslate").forGetter(Material::deepslate)
        ).apply(instance, Material::new));
        public Material {
            java.util.Objects.requireNonNull(deepslate);
            if (!validFossil(stone, FossilMaterialRules.Matrix.STONE)
                    || deepslate.filter(state -> !validFossil(state, FossilMaterialRules.Matrix.DEEPSLATE)).isPresent())
                throw new IllegalArgumentException("Coal materials require a fossil of the correct host, without fluid or block entities");
            if (deepslate.isPresent() && !FossilMaterialRules.compatiblePair(((FossilMaterial) stone.getBlock()).fossil(),
                    ((FossilMaterial) deepslate.get().getBlock()).fossil()))
                throw new IllegalArgumentException("Host variants must preserve fossil identity and form");
        }
        private static boolean validFossil(BlockState state, FossilMaterialRules.Matrix host) {
            return !state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                    && state.getBlock() instanceof FossilMaterial fossil && fossil.fitsHost(state, host);
        }
        Optional<BlockState> forHost(BlockState host) {
            return host.is(Blocks.DEEPSLATE) ? deepslate : host.is(Blocks.STONE) ? Optional.of(stone) : Optional.empty();
        }
    }
    public record Part(BlockPos offset, Material material) {
        public static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("offset").forGetter(Part::offset), Material.CODEC.fieldOf("material").forGetter(Part::material)
        ).apply(instance, Part::new));
        public Part { offset = offset.immutable(); java.util.Objects.requireNonNull(material); }
    }
    public record Template(ResourceLocation id, List<Part> parts) {
        public static final Codec<Template> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("id").forGetter(Template::id), Part.CODEC.listOf().fieldOf("parts").forGetter(Template::parts)
        ).apply(instance, Template::new));
        public Template {
            java.util.Objects.requireNonNull(id); parts = List.copyOf(parts);
            CoalCoreRules.anchorRange(parts.stream().map(Part::offset).toList(), CoalCoreRules.MINIMUM_Y, CoalCoreRules.MAXIMUM_Y + 1);
        }
    }
    public record Configuration(TagKey<Block> coalBlocks, TagKey<Biome> enrichedBiomes, List<Material> scattered,
            List<Template> structures, float structureChance, int baseAttempts, int enrichedAttempts, int maximumCores) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TagKey.codec(Registries.BLOCK).fieldOf("coal_blocks").forGetter(Configuration::coalBlocks),
                TagKey.codec(Registries.BIOME).fieldOf("enriched_biomes").forGetter(Configuration::enrichedBiomes),
                Material.CODEC.listOf().fieldOf("scattered").forGetter(Configuration::scattered),
                Template.CODEC.listOf().fieldOf("structures").forGetter(Configuration::structures),
                Codec.floatRange(0, 1).fieldOf("structure_chance").forGetter(Configuration::structureChance),
                Codec.intRange(1, 3).fieldOf("base_attempts").forGetter(Configuration::baseAttempts),
                Codec.intRange(1, 8).fieldOf("enriched_attempts").forGetter(Configuration::enrichedAttempts),
                Codec.intRange(2, 4).fieldOf("maximum_cores").forGetter(Configuration::maximumCores)
        ).apply(instance, Configuration::new));
        public Configuration {
            java.util.Objects.requireNonNull(coalBlocks); java.util.Objects.requireNonNull(enrichedBiomes);
            scattered = List.copyOf(scattered); structures = List.copyOf(structures);
            if (scattered.isEmpty() || scattered.size() > 16 || structures.size() > 16 || !Float.isFinite(structureChance)
                    || structureChance < 0 || structureChance > 1 || structureChance > 0 && structures.isEmpty()
                    || baseAttempts < 1 || baseAttempts > 3 || enrichedAttempts < 1 || enrichedAttempts > 8
                    || maximumCores <= baseAttempts || maximumCores > 4
                    || structures.stream().map(Template::id).distinct().count() != structures.size())
                throw new IllegalArgumentException("Invalid sparse coal-core generation settings");
        }
    }
    public CoalCoreFeature() { super(Configuration.CODEC); }
    @Override public boolean place(FeaturePlaceContext<Configuration> context) {
        var level = context.level(); var random = context.random(); var config = context.config();
        int chunkX = context.origin().getX() >> 4, chunkZ = context.origin().getZ() >> 4;
        if (!level.hasChunk(chunkX, chunkZ)) return false;
        int placed = 0;
        for (int attempt = 0; attempt < config.baseAttempts() + config.enrichedAttempts() && placed < config.maximumCores(); attempt++) {
            int x = (chunkX << 4) + random.nextInt(16), z = (chunkZ << 4) + random.nextInt(16);
            if (attempt >= config.baseAttempts()) {
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                var biome = level.getBiome(new BlockPos(x, Math.max(level.getMinBuildHeight(), surface), z));
                if (!biome.is(config.enrichedBiomes()) || biome.unwrapKey().filter(key -> key.location().getNamespace().equals("life_sanctuary")).isEmpty()) continue;
            }
            List<Part> parts = config.structureChance() > 0 && random.nextFloat() < config.structureChance()
                    ? config.structures().get(random.nextInt(config.structures().size())).parts()
                    : List.of(new Part(BlockPos.ZERO, config.scattered().get(random.nextInt(config.scattered().size()))));
            var offsets = parts.stream().map(Part::offset).toList();
            CoalCoreRules.AnchorRange range;
            try { range = CoalCoreRules.anchorRange(offsets, level.getMinBuildHeight(), level.getMaxBuildHeight()); }
            catch (IllegalArgumentException impossible) { continue; }
            var anchor = new BlockPos(x, range.minimum() + random.nextInt(range.maximum() - range.minimum() + 1), z);
            int turns = random.nextInt(4);
            var rotation = switch (turns) { case 1 -> Rotation.CLOCKWISE_90; case 2 -> Rotation.CLOCKWISE_180; case 3 -> Rotation.COUNTERCLOCKWISE_90; default -> Rotation.NONE; };
            var positions = CoalCoreRules.positions(anchor, offsets, turns);
            var expected = new LinkedHashMap<BlockPos, BlockState>(); var replacements = new LinkedHashMap<BlockPos, BlockState>();
            boolean valid = true;
            for (int i = 0; i < positions.size(); i++) {
                var pos = positions.get(i);
                if (level.isOutsideBuildHeight(pos) || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || !level.ensureCanWrite(pos)) { valid = false; break; }
                var old = level.getBlockState(pos); var replacement = parts.get(i).material().forHost(old);
                if (old.hasBlockEntity() || !old.getFluidState().isEmpty() || replacement.isEmpty()) { valid = false; break; }
                var next = replacement.get().rotate(level, pos, rotation);
                var host = old.is(Blocks.DEEPSLATE) ? FossilMaterialRules.Matrix.DEEPSLATE : FossilMaterialRules.Matrix.STONE;
                if (!next.is(config.coalBlocks()) || !Material.validFossil(next, host) || next.equals(old)) { valid = false; break; }
                expected.put(pos, old); replacements.put(pos, next);
            }
            if (!valid) continue;
            var result = CoalCoreRules.apply(expected, replacements, new CoalCoreRules.Access<BlockState>() {
                @Override public BlockState read(BlockPos pos) { return level.getBlockState(pos); }
                @Override public boolean write(BlockPos pos, BlockState state) { return level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); }
            });
            if (result == CoalCoreRules.Result.APPLIED) placed++;
            else if (result == CoalCoreRules.Result.RECOVERY_REQUIRED) {
                org.slf4j.LoggerFactory.getLogger(CoalCoreFeature.class).warn("Coal core could not fully roll back at {}", anchor);
                break;
            }
        }
        return placed > 0;
    }
}
