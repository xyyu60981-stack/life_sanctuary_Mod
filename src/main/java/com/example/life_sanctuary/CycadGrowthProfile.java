package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;

/** Shared cycad metadata; topology, bud/leaf models and reproductive yields remain explicit species adapters. */
public record CycadGrowthProfile(ResourceLocation species, int minimumHeight, int maximumHeight, int branchAboveHeight,
        int minimumBranchBudget, int maximumBranchBudget, int minimumNaturalMembers, int maximumNaturalMembers,
        int minimumSubadultDays, int maximumSubadultDays, boolean requiredBranches) {
    public CycadGrowthProfile(ResourceLocation species, int minimumHeight, int maximumHeight, int branchAboveHeight,
            int minimumBranchBudget, int maximumBranchBudget, int minimumNaturalMembers, int maximumNaturalMembers,
            int minimumSubadultDays, int maximumSubadultDays) {
        this(species, minimumHeight, maximumHeight, branchAboveHeight, minimumBranchBudget, maximumBranchBudget,
                minimumNaturalMembers, maximumNaturalMembers, minimumSubadultDays, maximumSubadultDays, false);
    }
    public static final String SEED = "SEED", SAPLING = "SAPLING", SUBADULT = "SUBADULT", ADULT = "ADULT", OFFSHOOT = "OFFSHOOT";
    public static final CycadGrowthProfile COMMON = new CycadGrowthProfile(
            ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "cycas"), 3, 8, 5, 1, 2, 5, 12, 1, 2);
    public CycadGrowthProfile {
        java.util.Objects.requireNonNull(species);
        if (minimumHeight < 1 || maximumHeight < minimumHeight || maximumHeight > 1024 || branchAboveHeight < 0
                || minimumBranchBudget < 1 || maximumBranchBudget < minimumBranchBudget || maximumBranchBudget > 16
                || minimumNaturalMembers < 1 || maximumNaturalMembers < minimumNaturalMembers
                || minimumSubadultDays < 1 || maximumSubadultDays < minimumSubadultDays)
            throw new IllegalArgumentException("Invalid cycad profile");
        Math.multiplyExact(maximumSubadultDays, 24_000);
    }
    public TagKey<Biome> naturalBiomes() {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(species.getNamespace(), species.getPath() + "_biomes"));
    }
    public enum LeafPalette { DARK_GREEN }
    public LeafPalette leafPalette() { return LeafPalette.DARK_GREEN; }
    public boolean acceptsBonemeal() { return false; }
    public int sampleHeight(RandomSource random) { return minimumHeight + random.nextInt(maximumHeight - minimumHeight + 1); }
    /** The budget is permission for a later topology plan, not a mandatory branch or a probability. */
    public int sampleBranchBudget(int height, RandomSource random) {
        requireHeight(height);
        return height <= branchAboveHeight ? 0 : minimumBranchBudget + random.nextInt(maximumBranchBudget - minimumBranchBudget + 1);
    }
    public boolean permitsShape(int height, int branches) {
        return height >= minimumHeight && height <= maximumHeight
                && branches >= (requiredBranches && height > branchAboveHeight ? minimumBranchBudget : 0)
                && branches <= (height <= branchAboveHeight ? 0 : maximumBranchBudget);
    }
    private void requireHeight(int height) { if (height < minimumHeight || height > maximumHeight) throw new IllegalArgumentException("Cycad height outside profile"); }
    public int sampleNaturalMembers(RandomSource random) { return minimumNaturalMembers + random.nextInt(maximumNaturalMembers - minimumNaturalMembers + 1); }
    public PlantSpeciesDefinition definition() {
        return new PlantSpeciesDefinition(species, Map.of(
                SEED, PlantSpeciesDefinition.StageDefinition.growing(SAPLING, 24_000, 72_000),
                SAPLING, PlantSpeciesDefinition.StageDefinition.growing(SUBADULT, 48_000, 96_000),
                SUBADULT, PlantSpeciesDefinition.StageDefinition.growing(ADULT, minimumSubadultDays * 24_000, maximumSubadultDays * 24_000),
                OFFSHOOT, PlantSpeciesDefinition.StageDefinition.growing(SUBADULT, 24_000, 72_000),
                ADULT, PlantSpeciesDefinition.StageDefinition.adult()), Optional.of(new PlantSpeciesDefinition.PopulationRules(32, maximumNaturalMembers)));
    }
    public void registerDefinition() {
        PlantSpeciesRegistry.registerCycad(definition());
        PlantSporeTraits.register(species, new PlantSporeTraits.Profile(PlantSporeTraits.Group.SEED_PLANT, false));
        PlantSpeciesRegistry.registerSpreadRule(species, new PlantSpreadAdmission(java.util.Set.of(SEED, SAPLING, SUBADULT, ADULT)));
        PlantGrowthSpace.register(species, SAPLING, PlantGrowthSpace.cycad(1));
        PlantGrowthSpace.register(species, SUBADULT, PlantGrowthSpace.cycad(2));
    }
}
