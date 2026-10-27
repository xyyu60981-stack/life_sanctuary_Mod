package com.example.life_sanctuary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Future structure-specific searches must return their nearest non-excluded real start, within an explicit budget. */
public final class ShelterLocators {
    private ShelterLocators() {}
    @FunctionalInterface public interface Locator {
        Optional<StructureStart> nearest(ServerLevel level, BlockPos origin, boolean skylight, Set<GlobalPos> excluded);
    }
    private static final Map<ResourceLocation, Locator> LOCATORS = new LinkedHashMap<>();
    /** Setup only; do not add locators while a world query is running. */
    public static void register(ResourceLocation id, Locator locator) {
        java.util.Objects.requireNonNull(id); java.util.Objects.requireNonNull(locator);
        if (LOCATORS.putIfAbsent(id, locator) != null) throw new IllegalArgumentException("Duplicate shelter locator " + id);
    }
    public static Optional<ShelterLocations.Target> find(ServerLevel level, BlockPos origin, boolean sky, Set<GlobalPos> excluded) {
        var index = ShelterLocations.get(level.getServer());
        if (index.isReadOnly()) return Optional.empty();
        var exclusions = Set.copyOf(excluded);
        for (var locator : LOCATORS.values()) {
            var candidate = locator.nearest(level, origin.immutable(), sky, exclusions);
            if (candidate.isPresent()) index.observe(level, candidate.get());
        }
        return index.nearest(level.dimension(), origin, sky, exclusions);
    }
}
