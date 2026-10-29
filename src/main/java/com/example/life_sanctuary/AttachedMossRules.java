package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** No reproductive route or automatic-growth handler is registered for this species. */
public final class AttachedMossRules {
    private AttachedMossRules() {}
    public static final ResourceLocation SPECIES = ResourceLocation.fromNamespaceAndPath(LifeSanctuaryMod.MODID, "attached_moss");
    public static final int NATURAL_MIN = 1, NATURAL_MAX = 4, EMPTY_CHUNKS_BETWEEN = 1, PREFERRED_HEIGHT = 10;
    @SuppressWarnings("deprecation")
    public static void setup() {
        PlantSpeciesRegistry.register(new PlantSpeciesDefinition(SPECIES,
                Map.of("ADULT", PlantSpeciesDefinition.StageDefinition.adult()), Optional.empty()));
        PlantSporeTraits.register(SPECIES, new PlantSporeTraits.Profile(PlantSporeTraits.Group.BRYOPHYTE, false));
        net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.put(LifeSanctuaryMod.ATTACHED_MOSS_ITEM.get(), 0.6F);
    }
}
