package com.example.life_sanctuary;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** Species traits, not item/block tags. Seed-plant heterospory alone never enables lycopod game rules. */
public final class PlantSporeTraits {
    private PlantSporeTraits() {}
    public enum Group { UNCLASSIFIED, BRYOPHYTE, SEED_PLANT, FERN, SPHENOPHYTE, RHYNIOPHYTE, LYCOPOD, TREE_LYCOPOD }
    public enum Mode { UNCLASSIFIED, HOMOSPOROUS, HETEROSPOROUS }
    public enum Sex { FEMALE, MALE, MONOECIOUS }
    public enum TreeFamily { GUANGDE_WOOD, SCALE_TREE, SEAL_TREE, NEST_TREE, NAVEL_LYCOPOD }
    public record Profile(Group group, boolean allMonoecious) {
        public Profile {
            java.util.Objects.requireNonNull(group);
            if (allMonoecious && group != Group.TREE_LYCOPOD) throw new IllegalArgumentException("Mixed-only rule needs tree lycopods");
        }
        public Mode mode() { return switch (group) {
            case SEED_PLANT, TREE_LYCOPOD -> Mode.HETEROSPOROUS;
            case FERN, SPHENOPHYTE, RHYNIOPHYTE, LYCOPOD -> Mode.HOMOSPOROUS;
            default -> Mode.UNCLASSIFIED;
        }; }
        public boolean differentiatedSpores() { return group == Group.TREE_LYCOPOD; }
    }
    private static final Profile UNKNOWN = new Profile(Group.UNCLASSIFIED, false);
    private static final Map<ResourceLocation, Profile> PROFILES = new ConcurrentHashMap<>();
    public static void register(ResourceLocation species, Profile profile) {
        java.util.Objects.requireNonNull(profile);
        var definition = PlantSpeciesRegistry.find(species).orElseThrow(
                () -> new IllegalArgumentException("Spore traits need a registered species"));
        if (profile.differentiatedSpores() && definition.population().isEmpty())
            throw new IllegalArgumentException("Tree lycopod heterospory requires a population region");
        if (PROFILES.putIfAbsent(species, profile) != null) throw new IllegalArgumentException("Duplicate spore traits " + species);
    }
    public static void inherit(ResourceLocation child, ResourceLocation parent) {
        var profile = PROFILES.get(parent);
        if (profile == null) throw new IllegalArgumentException("Missing parent spore traits");
        register(child, profile);
    }
    public static Profile tree(TreeFamily family) {
        java.util.Objects.requireNonNull(family);
        return new Profile(Group.TREE_LYCOPOD, family == TreeFamily.NEST_TREE || family == TreeFamily.NAVEL_LYCOPOD);
    }
    public static Profile find(ResourceLocation species) { return PROFILES.getOrDefault(species, UNKNOWN); }
    public static Optional<ResourceLocation> species(String label) {
        if (label == null || label.isBlank() || label.length() > 256) return Optional.empty();
        return Optional.ofNullable(ResourceLocation.tryParse(label.contains(":") ? label : LifeSanctuaryMod.MODID + ":" + label));
    }
    public static Sex sampleSex(Profile profile, int roll) {
        if (!profile.differentiatedSpores() || roll < 0 || roll >= 100) throw new IllegalArgumentException("Invalid tree lycopod sex sample");
        return profile.allMonoecious() ? Sex.MONOECIOUS : roll < 50 ? Sex.FEMALE : roll < 80 ? Sex.MALE : Sex.MONOECIOUS;
    }
    public static SporeBagContents.Form form(Sex sex) { return switch (sex) {
        case FEMALE -> SporeBagContents.Form.MEGA; case MALE -> SporeBagContents.Form.MICRO; case MONOECIOUS -> SporeBagContents.Form.MIXED;
    }; }
    public static float coneScale(Sex sex) { return sex == Sex.MALE ? 0.8F : 1.0F; }
}
