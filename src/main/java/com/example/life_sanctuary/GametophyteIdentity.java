package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Server identity lookup for shared gametophyte geometry. Never infers species from a shared block ID. */
public record GametophyteIdentity(ResourceLocation species, SporeBagContents.Form form,
        GametophyteAppearance appearance) {
    public GametophyteIdentity {
        java.util.Objects.requireNonNull(species);
        java.util.Objects.requireNonNull(form);
        java.util.Objects.requireNonNull(appearance);
        if (GametophyteAppearance.forSpecies(species, form).filter(appearance::equals).isEmpty())
            throw new IllegalArgumentException("Gametophyte species, spore form and appearance disagree");
    }
    private static final Map<ResourceLocation, String> STAGES = new ConcurrentHashMap<>();
    static boolean isRegisteredStage(ResourceLocation species, String stage) {
        return stage != null && stage.equals(STAGES.get(species));
    }

    /** Homosporous species explicitly declare their shared-gametophyte stage at setup. */
    public static void registerStage(ResourceLocation species, String stage) {
        var definition = PlantSpeciesRegistry.find(species).orElseThrow();
        if (stage == null || !definition.stages().containsKey(stage)
                || definition.stages().get(stage).fullyAdult()
                || GametophyteAppearance.forSpecies(species, SporeBagContents.Form.NORMAL).isEmpty())
            throw new IllegalArgumentException("Missing homosporous gametophyte stage");
        if (STAGES.putIfAbsent(species, stage) != null) throw new IllegalArgumentException("Duplicate gametophyte stage");
    }

    public static Optional<GametophyteIdentity> at(ServerLevel level, BlockPos pos) {
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return Optional.empty();
        // Compatibility with the existing horsetail save format, until its explicit migration is installed.
        if (level.getBlockState(pos).is(LifeSanctuaryMod.EQUISETUM_ARVENSE_GAMETOPHYTE.get()))
            return create(PlantSpeciesRegistry.HORSETAIL.id(), SporeBagContents.Form.NORMAL);
        if (level.getBlockState(pos).is(LifeSanctuaryMod.SPHENOPHYTE_GAMETOPHYTE.get())
                && HorsetailPopulations.get(level).member(pos) != null)
            return create(PlantSpeciesRegistry.HORSETAIL.id(), SporeBagContents.Form.NORMAL);
        var store = PlantIndividuals.get(level);
        if (store.isReadOnly()) return Optional.empty();
        var plant = store.atPart(pos).orElse(null);
        if (plant == null) return Optional.empty();
        if (!level.getChunkSource().hasChunk(plant.base().getX() >> 4, plant.base().getZ() >> 4)) return Optional.empty();
        var baseState = level.getBlockState(plant.base());
        var adapter = HeterosporeAdapters.find(plant.species());
        if (PlantSporeTraits.find(plant.species()).differentiatedSpores()) {
            if (adapter == null || !adapter.gametophyteStage().equals(plant.stage())) return Optional.empty();
            var cycle = HeterosporeWorldData.get(level).cycle(plant.id()).orElse(null);
            if (cycle == null) return Optional.empty();
            var form = switch (cycle.kind()) {
                case FEMALE -> SporeBagContents.Form.MEGA;
                case MALE -> SporeBagContents.Form.MICRO;
                case MIXED -> SporeBagContents.Form.MIXED;
            };
            return SharedGametophyteBlocks.permits(plant.species(), form, baseState)
                    ? create(plant.species(), form) : Optional.empty();
        }
        return plant.stage().equals(STAGES.get(plant.species()))
                && SharedGametophyteBlocks.permits(plant.species(), SporeBagContents.Form.NORMAL, baseState)
                ? create(plant.species(), SporeBagContents.Form.NORMAL) : Optional.empty();
    }

    private static Optional<GametophyteIdentity> create(ResourceLocation species, SporeBagContents.Form form) {
        return GametophyteAppearance.forSpecies(species, form).map(view -> new GametophyteIdentity(species, form, view));
    }
}
