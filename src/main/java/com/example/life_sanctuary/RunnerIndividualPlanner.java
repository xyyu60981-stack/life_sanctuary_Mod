package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Builds state-only cut/merge proposals. Species adapters remain responsible for physical world edits. */
public final class RunnerIndividualPlanner {
    private RunnerIndividualPlanner() {}

    /** Explicit identities avoid imposing a species policy for which fragment keeps the original UUID. */
    public record Identity(UUID id, BlockPos base) {
        public Identity {
            java.util.Objects.requireNonNull(id);
            base = base.immutable();
        }
    }

    public record CutPlan(PlantIndividualReplacement replacement,
            Map<BlockPos, String> expectedParts, Set<BlockPos> removedParts,
            Map<UUID, PlantRunnerNetwork> networks) {
        public CutPlan {
            expectedParts = copyParts(expectedParts);
            removedParts = removedParts.stream().map(BlockPos::immutable)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            networks = Map.copyOf(networks);
        }

        /**
         * Server thread, after the adapter has handled world removal successfully.
         * Checks ownership too: attaching/removing a leaf need not change the individual snapshot.
         * This does not verify or roll back world edits and must not be used as a world transaction.
         */
        public boolean commitState(PlantIndividuals store) {
            if (store.isReadOnly()) return false;
            var expected = replacement.expected().getFirst();
            if (!store.partsOf(expected.id()).equals(expectedParts)) return false;
            return store.replaceGroup(replacement);
        }
    }

    public record MergePlan(PlantIndividualReplacement replacement,
            Map<UUID, Map<BlockPos, String>> expectedParts, PlantRunnerNetwork network) {
        public MergePlan {
            java.util.Objects.requireNonNull(network);
            if (replacement.expected().size() != 2 || replacement.replacements().size() != 1)
                throw new IllegalArgumentException("Merge requires two originals and one result");
            Map<UUID, Map<BlockPos, String>> copied = new HashMap<>();
            expectedParts.forEach((id, parts) -> copied.put(id, copyParts(parts)));
            expectedParts = Map.copyOf(copied);
            var expectedIds = replacement.expected().stream().map(PlantIndividualState::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (expectedIds.size() != 2 || !expectedParts.keySet().equals(expectedIds))
                throw new IllegalArgumentException("Missing original structure snapshot");
        }

        /** State-only: the adapter must validate physical contact and coordinate any world updates. */
        public boolean commitState(PlantIndividuals store) {
            if (store.isReadOnly()) return false;
            for (var entry : expectedParts.entrySet())
                if (!store.partsOf(entry.getKey()).equals(entry.getValue())) return false;
            return store.replaceGroup(replacement);
        }
    }

    /**
     * Join already-adjacent runners, never invent a bridging block. Empty means invalid topology
     * (disconnected, loop or excessive branching); overlapping ownership and inconsistent inputs throw.
     * The caller explicitly chooses the resulting UUID/base; no resource keys are renamed.
     */
    public static Optional<MergePlan> merge(PlantIndividualState first, PlantRunnerNetwork firstNetwork,
            Map<BlockPos, String> firstParts, PlantIndividualState second, PlantRunnerNetwork secondNetwork,
            Map<BlockPos, String> secondParts, Identity identity) {
        return merge(first, firstNetwork, firstParts, second, secondNetwork, secondParts, identity, Optional.empty());
    }

    /** Initialized runners require an explicit non-additive merged budget from the species policy. */
    public static Optional<MergePlan> merge(PlantIndividualState first, PlantRunnerNetwork firstNetwork,
            Map<BlockPos, String> firstParts, PlantIndividualState second, PlantRunnerNetwork secondNetwork,
            Map<BlockPos, String> secondParts, Identity identity, Optional<RunnerGrowthAdmission.Budget> budget) {
        if (first.id().equals(second.id())) throw new IllegalArgumentException("Cannot merge an individual with itself");
        PlantIndividuals.requireCompatible(first, second);
        if (budget.isPresent() != first.runnerBudget().isPresent())
            throw new IllegalArgumentException("Explicit merged runner budget required");
        budget.ifPresent(value -> PlantIndividuals.requireMergedRunnerBudget(List.of(first, second), value));
        firstParts = copyParts(firstParts);
        secondParts = copyParts(secondParts);
        if (!firstParts.keySet().containsAll(firstNetwork.positions())
                || !secondParts.keySet().containsAll(secondNetwork.positions()))
            throw new IllegalArgumentException("Runner segments missing from owned structures");
        Map<BlockPos, String> joinedParts = new HashMap<>(firstParts);
        for (var entry : secondParts.entrySet())
            if (joinedParts.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IllegalArgumentException("Original structures overlap");

        var merged = RunnerStateInheritance.merge(
                new RunnerStateInheritance.Part(firstNetwork, first.harvests(),
                        new RunnerStateInheritance.Timer(first.targetTicks(), first.elapsedTicks()), first.reproduction()),
                new RunnerStateInheritance.Part(secondNetwork, second.harvests(),
                        new RunnerStateInheritance.Timer(second.targetTicks(), second.elapsedTicks()), second.reproduction()));
        if (merged.isEmpty()) return Optional.empty();
        var part = merged.get();
        if (!part.network().positions().contains(identity.base()))
            throw new IllegalArgumentException("Merged base must belong to the resulting runner");
        var state = new PlantIndividualState(identity.id(), first.species(), identity.base(), first.source(),
                first.stage(), first.population(), first.canBePruned(), first.pruned(),
                part.timer().target(), part.timer().elapsed(), first.environmentReasons(), part.harvests(),
                first.origin(), part.reproduction(), budget);
        var replacement = new PlantIndividualReplacement(List.of(first, second), List.of(state),
                Map.of(identity.id(), joinedParts), Map.of(), Map.of());
        return Optional.of(new MergePlan(replacement,
                Map.of(first.id(), firstParts, second.id(), secondParts), part.network()));
    }

    /**
     * Every owned part, including off-runner leaves, names one attachment segment.
     * Identities correspond to the stable component order returned by network.remove(removed).
     * Resource keys stay unchanged even when a fragment receives a new identity.
     */
    public static CutPlan cut(PlantIndividualState expected, PlantRunnerNetwork network, BlockPos removed,
            Map<BlockPos, String> ownedParts, Map<BlockPos, BlockPos> partAnchors,
            Map<String, BlockPos> harvestAnchors, Map<String, BlockPos> reproductionAnchors,
            List<Identity> identities) {
        return cut(expected, network, removed, ownedParts, partAnchors, harvestAnchors,
                reproductionAnchors, identities, Map.of());
    }

    /** Fresh base/branch rolls are supplied once per true fragment, never generated during validation. */
    public static CutPlan cut(PlantIndividualState expected, PlantRunnerNetwork network, BlockPos removed,
            Map<BlockPos, String> ownedParts, Map<BlockPos, BlockPos> partAnchors,
            Map<String, BlockPos> harvestAnchors, Map<String, BlockPos> reproductionAnchors,
            List<Identity> identities, Map<UUID, RunnerGrowthAdmission.Budget> splitBudgets) {
        ownedParts = copyParts(ownedParts);
        identities = List.copyOf(identities);
        splitBudgets = Map.copyOf(splitBudgets);
        if (!network.positions().contains(removed))
            throw new IllegalArgumentException("Cut must remove an existing runner segment");
        if (!ownedParts.keySet().containsAll(network.positions())
                || !ownedParts.keySet().equals(partAnchors.keySet()))
            throw new IllegalArgumentException("Every owned part needs exactly one attachment");
        for (var entry : partAnchors.entrySet()) {
            if (entry.getValue() == null || !network.positions().contains(entry.getValue())
                    || (network.positions().contains(entry.getKey()) && !entry.getKey().equals(entry.getValue())))
                throw new IllegalArgumentException("Invalid runner attachment");
        }
        var inherited = RunnerStateInheritance.cut(network, removed, expected.harvests(), harvestAnchors,
                expected.reproduction(), reproductionAnchors,
                new RunnerStateInheritance.Timer(expected.targetTicks(), expected.elapsedTicks()));
        if (identities.size() != inherited.parts().size())
            throw new IllegalArgumentException("Exactly one identity required per surviving component");
        boolean resetBudget = inherited.reallySplit() && expected.runnerBudget().isPresent();
        var requiredIds = identities.stream().map(Identity::id).collect(java.util.stream.Collectors.toSet());
        if (resetBudget ? !splitBudgets.keySet().equals(requiredIds) : !splitBudgets.isEmpty())
            throw new IllegalArgumentException("Supply budgets only for every true split fragment");
        if (splitBudgets.values().stream().anyMatch(budget -> budget.extraLength() != 0 || budget.peatRewardsUsed() != 0))
            throw new IllegalArgumentException("Split cannot inherit extra length rewards");

        Map<BlockPos, Integer> components = new HashMap<>();
        Map<UUID, PlantRunnerNetwork> networks = new HashMap<>();
        List<PlantIndividualState> states = new ArrayList<>();
        List<Map<BlockPos, String>> allocatedParts = new ArrayList<>();
        for (int i = 0; i < identities.size(); i++) {
            var identity = identities.get(i);
            var part = inherited.parts().get(i);
            if (!part.network().positions().contains(identity.base())
                    || networks.putIfAbsent(identity.id(), part.network()) != null)
                throw new IllegalArgumentException("Duplicate identity or base outside its fragment");
            int component = i;
            part.network().positions().forEach(pos -> components.put(pos, component));
            allocatedParts.add(new HashMap<>());
            states.add(new PlantIndividualState(identity.id(), expected.species(), identity.base(), expected.source(),
                    expected.stage(), expected.population(), expected.canBePruned(), expected.pruned(),
                    part.timer().target(), part.timer().elapsed(), expected.environmentReasons(), part.harvests(),
                    expected.origin(), part.reproduction(), resetBudget
                            ? Optional.of(splitBudgets.get(identity.id()).withStarts(expected.runnerBudget().orElseThrow()
                                    .survivingStarts(part.network().positions())))
                            : expected.runnerBudget().map(value -> value.withStarts(value.survivingStarts(part.network().positions())))));
        }
        Set<BlockPos> removedParts = new HashSet<>();
        for (var entry : ownedParts.entrySet()) {
            Integer component = components.get(partAnchors.get(entry.getKey()));
            if (component == null) removedParts.add(entry.getKey());
            else allocatedParts.get(component).put(entry.getKey(), entry.getValue());
        }
        Map<UUID, Map<BlockPos, String>> structures = new HashMap<>();
        for (int i = 0; i < identities.size(); i++) structures.put(identities.get(i).id(), allocatedParts.get(i));
        var replacement = new PlantIndividualReplacement(List.of(expected), states, structures,
                inherited.removedHarvests(), inherited.removedReproduction());
        return new CutPlan(replacement, ownedParts, removedParts, networks);
    }

    private static Map<BlockPos, String> copyParts(Map<BlockPos, String> parts) {
        Map<BlockPos, String> copied = new HashMap<>();
        parts.forEach((pos, role) -> {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("Empty structure role");
            copied.put(pos.immutable(), role);
        });
        return Map.copyOf(copied);
    }
}
