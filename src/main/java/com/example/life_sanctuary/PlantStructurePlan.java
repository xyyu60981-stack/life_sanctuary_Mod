package com.example.life_sanctuary;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Immutable bounded read/write set prepared by a species adapter. Dependencies include unchanged
 * support blocks. Plans are ephemeral, not stored across ticks or used as proof that placement succeeded.
 */
public record PlantStructurePlan(PlantIndividualState expectedIndividual, Map<BlockPos, Change> changes,
        Map<BlockPos, BlockState> dependencies) {
    public enum Replacement {
        AIR_ONLY,
        REPLACEABLE_DRY,
        OWNED_PART,
        WATER_PRESERVING,
        OWNED_WATER_PART;

        public boolean requiresOwnership() { return this == OWNED_PART || this == OWNED_WATER_PART; }
        public boolean preservesWater() { return this == WATER_PRESERVING || this == OWNED_WATER_PART; }
    }

    public record Change(BlockState expected, BlockState replacement, String role, Replacement policy) {
        /** The preflight still verifies actual ownership; this helper never claims a world position. */
        public static Change removeOwned(BlockState expected) {
            var fluid = expected.getFluidState();
            if (fluid.isEmpty()) return new Change(expected, Blocks.AIR.defaultBlockState(), "", Replacement.OWNED_PART);
            if (fluid.is(FluidTags.WATER))
                return new Change(expected, fluid.createLegacyBlock(), "", Replacement.OWNED_WATER_PART);
            throw new IllegalArgumentException("Unsupported fluid in plant removal");
        }

        public Change {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(replacement, "replacement");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(policy, "policy");
            // Removing a wet structure restores its fluid block instead of turning water into air.
            if (!role.isEmpty() && role.isBlank()) throw new IllegalArgumentException("Whitespace role");
            if (role.isEmpty()) {
                boolean dryRemoval = replacement.isAir() && policy == Replacement.OWNED_PART;
                boolean wetRemoval = policy == Replacement.OWNED_WATER_PART
                        && expected.getFluidState().is(FluidTags.WATER)
                        && replacement.equals(expected.getFluidState().createLegacyBlock());
                if (!dryRemoval && !wetRemoval) throw new IllegalArgumentException("Invalid structural removal");
            }
            if (!role.isEmpty() && replacement.isAir())
                throw new IllegalArgumentException("Air cannot own a structure role");
            if (replacement.isAir() && policy != Replacement.OWNED_PART)
                throw new IllegalArgumentException("Removal must target an owned part");
            if (policy.preservesWater()) {
                if (!expected.getFluidState().is(FluidTags.WATER)
                        || !expected.getFluidState().equals(replacement.getFluidState()))
                    throw new IllegalArgumentException("Wet changes must preserve the exact water state");
                if (!role.isEmpty() && replacement.equals(expected.getFluidState().createLegacyBlock()))
                    throw new IllegalArgumentException("Plain water cannot own a plant structure role");
            }
        }
    }

    public PlantStructurePlan {
        Objects.requireNonNull(expectedIndividual, "expectedIndividual");
        changes = immutablePositions(changes);
        dependencies = immutablePositions(dependencies);
        if (changes.isEmpty()) throw new IllegalArgumentException("Empty structure plan");
        for (var entry : changes.entrySet()) {
            BlockState dependency = dependencies.get(entry.getKey());
            if (dependency != null && !dependency.equals(entry.getValue().expected()))
                throw new IllegalArgumentException("Conflicting expected states");
        }
    }

    private static <T> Map<BlockPos, T> immutablePositions(Map<BlockPos, T> source) {
        Map<BlockPos, T> copy = new LinkedHashMap<>();
        source.forEach((pos, value) -> copy.put(pos.immutable(), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }

    /** Complete local view for species-specific support/shape checks; never reads or loads the world. */
    public Map<BlockPos, BlockState> plannedStates() {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>(dependencies);
        changes.forEach((pos, change) -> states.put(pos, change.replacement()));
        return Collections.unmodifiableMap(states);
    }
}
