package com.example.life_sanctuary;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Implement on the species' seed block. This property is visual, never a germination requirement. */
public interface CycadSeedAppearance {
    BooleanProperty SEED_COAT = BooleanProperty.create("seed_coat");
    ResourceLocation species();
    String seedStage();
    int coatColor();
    static boolean matchesStage(BlockState state, ResourceLocation species, String stage) {
        return !(state.getBlock() instanceof CycadSeedAppearance seed) || state.hasProperty(SEED_COAT)
                && seed.species().equals(species) && seed.seedStage().equals(stage);
    }
    static boolean permits(PlantStructurePlan plan, PlantIndividualState next) {
        return plan.changes().values().stream().filter(change -> !change.role().isEmpty())
                .allMatch(change -> matchesStage(change.replacement(), next.species(), next.stage()));
    }
}
