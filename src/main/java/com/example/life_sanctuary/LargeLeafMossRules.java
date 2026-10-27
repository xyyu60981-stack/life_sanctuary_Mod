package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

public final class LargeLeafMossRules {
    private LargeLeafMossRules() {}
    public static final ResourceLocation SPECIES = ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "large_leaf_moss");
    public static final String STAGE = "MOSS";
    public static final int DAY = 24_000, LIMIT = 8;
    public static void setup() {
        PlantSpeciesRegistry.register(new PlantSpeciesDefinition(SPECIES, Map.of(STAGE, PlantSpeciesDefinition.StageDefinition.terminal()),
                Optional.of(new PlantSpeciesDefinition.PopulationRules(16, LIMIT))));
        PlantSpeciesRegistry.registerSpreadRule(SPECIES, new PlantSpreadAdmission(java.util.Set.of(STAGE), true));
        PlantSporeTraits.register(SPECIES, new PlantSporeTraits.Profile(PlantSporeTraits.Group.BRYOPHYTE, false));
    }
    public static int naturalDensity(int roll) {
        if (roll < 0 || roll >= 100) throw new IllegalArgumentException("Invalid moss density roll");
        return roll < 30 ? 1 : roll < 70 ? 2 : 3;
    }
    public static int nextDelay(int density, RandomSource random) {
        if (density < 1 || density > 3) throw new IllegalArgumentException("Invalid moss density");
        return density == 3 ? DAY : DAY + random.nextInt(DAY + 1);
    }
    public static String population(net.minecraft.core.BlockPos pos) {
        return PlantPopulationTag.fromBase("large_leaf_moss", pos, 16).serializedName();
    }
}
