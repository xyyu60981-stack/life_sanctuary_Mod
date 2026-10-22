package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension individual store. No world scans, chunk loads, timer advancement or automatic
 * registration occur during reads. Unknown species/stages remain preserved for optional modules.
 */
public final class PlantIndividuals extends SavedData {
    private static final int VERSION = 5;
    private static final Factory<PlantIndividuals> FACTORY = new Factory<>(PlantIndividuals::new, PlantIndividuals::load);
    private final Map<UUID, PlantIndividualState> individuals = new HashMap<>();
    private final Map<BlockPos, UUID> byBase = new HashMap<>();
    private final Map<BlockPos, UUID> partOwners = new HashMap<>();
    private final Map<UUID, Map<BlockPos, String>> parts = new HashMap<>();
    private final PlantGrowthScheduler growth = new PlantGrowthScheduler();
    private final PlantPopulationIndex populationIndex = new PlantPopulationIndex();
    private CompoundTag preservedUnsupportedData;
    private UUID applyingStructure;

    public static PlantIndividuals get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_plant_individuals");
    }

    public boolean isReadOnly() { return preservedUnsupportedData != null; }
    public boolean isApplyingStructure(UUID id) { return id.equals(applyingStructure); }

    void beginStructure(PlantIndividualState expected) {
        writable();
        if (!expected.equals(find(expected.id()).orElse(null)))
            throw new IllegalStateException("Stale structure snapshot");
        applyingStructure = expected.id();
    }

    void endStructure() { applyingStructure = null; }

    /** Called only after all world writes have succeeded while the store is locked. */
    void commitStructure(PlantStructurePlan plan, PlantIndividualState next,
            PlantReproductionDebit debit) {
        if (!isApplyingStructure(next.id())
                || !plan.expectedIndividual().equals(find(next.id()).orElse(null)))
            throw new IllegalStateException("Structure commit lost its owner");
        PlantIndividualState parentAfter = null;
        if (debit != null) {
            if (debit.expected().id().equals(next.id())
                    || !debit.expected().equals(find(debit.expected().id()).orElse(null)))
                throw new IllegalStateException("Reproduction parent changed before commit");
            parentAfter = debit.after();
        }
        Map<BlockPos, String> updated = new HashMap<>(parts.getOrDefault(next.id(), Map.of()));
        plan.changes().forEach((pos, change) -> {
            if (change.role().isEmpty()) updated.remove(pos);
            else updated.put(pos, change.role());
        });
        // Ownership was checked before locking; public mutations are rejected during world callbacks.
        Map<BlockPos, String> old = parts.remove(next.id());
        if (old != null) old.keySet().forEach(pos -> partOwners.remove(pos, next.id()));
        if (!updated.isEmpty()) {
            parts.put(next.id(), updated);
            updated.keySet().forEach(pos -> partOwners.put(pos, next.id()));
        }
        populationIndex.changed(plan.expectedIndividual(), next);
        individuals.put(next.id(), next);
        growth.update(next);
        // Both records share this SavedData payload. No world/species callbacks between these writes.
        // Only the budget changes: the parent's existing timer and population index remain valid.
        if (parentAfter != null) individuals.put(parentAfter.id(), parentAfter);
        setDirty();
    }
    public Optional<PlantIndividualState> find(UUID id) {
        PlantIndividualState state = individuals.get(id);
        return state == null ? Optional.empty() : Optional.of(growth.current(state));
    }

    public void tickGrowth(ServerLevel level) {
        if (!isReadOnly()) growth.tick(level, this);
    }

    /** Counts registered individuals only; does not discover unregistered natural scenery. */
    public PlantPopulationCounts populationCounts(ResourceLocation species, String population) {
        if (isReadOnly()) throw new IllegalStateException("Population data is unavailable in read-only recovery mode");
        return populationIndex.snapshot(species, population);
    }
    public Optional<PlantIndividualState> atBase(BlockPos pos) {
        UUID id = byBase.get(pos);
        return id == null ? Optional.empty() : find(id);
    }

    /** Base is an identity anchor; structural positions are registered explicitly. */
    public Optional<PlantIndividualState> atPart(BlockPos pos) {
        UUID id = partOwners.get(pos);
        return id == null ? Optional.empty() : find(id);
    }

    public Map<BlockPos, String> partsOf(UUID id) {
        return Map.copyOf(parts.getOrDefault(id, Map.of()));
    }

    /** Validate the entire batch before changing any index. Existing same-owner bindings are idempotent. */
    public void attachParts(UUID id, Map<BlockPos, String> additions) {
        writable();
        if (!individuals.containsKey(id)) throw new IllegalArgumentException("Unknown individual");
        Map<BlockPos, String> normalized = new HashMap<>();
        additions.forEach((pos, role) -> {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("Empty structure role");
            BlockPos key = pos.immutable();
            UUID owner = partOwners.get(key);
            UUID anchorOwner = byBase.get(key);
            if ((owner != null && !owner.equals(id)) || (anchorOwner != null && !anchorOwner.equals(id)))
                throw new IllegalArgumentException("Structure belongs to another individual");
            normalized.put(key, role);
        });
        if (normalized.isEmpty()) return;
        Map<BlockPos, String> owned = parts.computeIfAbsent(id, ignored -> new HashMap<>());
        boolean changed = false;
        for (var entry : normalized.entrySet()) {
            changed |= !entry.getValue().equals(owned.put(entry.getKey(), entry.getValue()));
            partOwners.put(entry.getKey(), id);
        }
        if (changed) setDirty();
    }

    /** Detaching a leaf/root never deletes the individual or replenishes any harvest quota. */
    public boolean detachPart(UUID expectedOwner, BlockPos pos) {
        writable();
        if (!expectedOwner.equals(partOwners.get(pos))) return false;
        partOwners.remove(pos);
        Map<BlockPos, String> owned = parts.get(expectedOwner);
        owned.remove(pos);
        if (owned.isEmpty()) parts.remove(expectedOwner);
        setDirty();
        return true;
    }

    /** Creation cannot overwrite an existing identity or occupied base. */
    public void create(PlantIndividualState state) {
        writable();
        if (individuals.containsKey(state.id()) || byBase.containsKey(state.base())
                || partOwners.containsKey(state.base()))
            throw new IllegalArgumentException("Individual or base already registered");
        individuals.put(state.id(), state);
        populationIndex.add(state);
        byBase.put(state.base(), state.id());
        growth.update(state);
        setDirty();
    }

    /** Optimistic replacement rejects stale snapshots; moving a plant requires an explicit later API. */
    public boolean replace(PlantIndividualState expected, PlantIndividualState next) {
        writable();
        if (!expected.id().equals(next.id()) || !expected.species().equals(next.species())
                || !expected.base().equals(next.base()))
            throw new IllegalArgumentException("Cannot replace identity, species or base");
        if (!expected.equals(find(expected.id()).orElse(null))) return false;
        if (!expected.equals(next)) {
            populationIndex.changed(expected, next);
            individuals.put(next.id(), next);
            growth.update(next);
            setDirty();
        }
        return true;
    }

    /** Both keys are required so a stale removal cannot delete a new plant at a reused position. */
    public boolean remove(UUID id, BlockPos base) {
        writable();
        PlantIndividualState current = individuals.get(id);
        if (current == null || !current.base().equals(base)) return false;
        individuals.remove(id);
        populationIndex.remove(current);
        growth.cancel(id);
        byBase.remove(base, id);
        Map<BlockPos, String> owned = parts.remove(id);
        if (owned != null) owned.keySet().forEach(pos -> partOwners.remove(pos, id));
        setDirty();
        return true;
    }

    private void writable() {
        if (isReadOnly()) throw new IllegalStateException("Unsupported or damaged plant data; migration required");
        if (applyingStructure != null) throw new IllegalStateException("Reentrant plant mutation during structure placement");
    }

    /**
     * Validated state-only split/merge. Server-thread only; no callbacks or world changes occur here.
     * Different sources/stages/pause states require a future explicit species policy.
     */
    public boolean replaceGroup(PlantIndividualReplacement proposal) {
        writable();
        Set<UUID> oldIds = new HashSet<>();
        Map<String, Integer> oldHarvests = new HashMap<>();
        Map<String, Integer> oldReproduction = new HashMap<>();
        var first = proposal.expected().getFirst();
        long target = 0;
        long remaining = 0;
        Set<BlockPos> oldParts = new HashSet<>();
        for (var old : proposal.expected()) {
            if (!oldIds.add(old.id()) || !old.equals(find(old.id()).orElse(null))) return false;
            requireCompatible(first, old);
            collectHarvests(oldHarvests, old.harvests());
            collectHarvests(oldReproduction, old.reproduction());
            target = Math.max(target, old.targetTicks());
            remaining = Math.max(remaining, old.remainingTicks());
            oldParts.addAll(parts.getOrDefault(old.id(), Map.of()).keySet());
        }
        Set<UUID> newIds = new HashSet<>();
        Set<BlockPos> newBases = new HashSet<>();
        Map<BlockPos, UUID> newOwners = new HashMap<>();
        Map<String, Integer> newHarvests = new HashMap<>();
        Map<String, Integer> newReproduction = new HashMap<>();
        for (var next : proposal.replacements()) {
            requireCompatible(first, next);
            if (!newIds.add(next.id()) || (!oldIds.contains(next.id()) && individuals.containsKey(next.id()))
                    || !newBases.add(next.base())) throw new IllegalArgumentException("Duplicate replacement identity/base");
            if (next.targetTicks() != target || next.remainingTicks() != remaining)
                throw new IllegalArgumentException("Replacement changed inherited timer");
            if (proposal.expected().size() == 1 && proposal.replacements().size() == 1
                    && !first.runnerBudget().equals(next.runnerBudget()))
                throw new IllegalArgumentException("Trimming cannot reset runner budget");
            if (proposal.replacements().size() > 1 && next.runnerBudget().isPresent()
                    && next.runnerBudget().get().extraLength() != 0)
                throw new IllegalArgumentException("True splits must discard extra runner length");
            if (proposal.expected().size() > 1)
                next.runnerBudget().ifPresent(budget -> requireMergedRunnerBudget(proposal.expected(), budget));
            UUID baseOwner = byBase.get(next.base());
            UUID partOwner = partOwners.get(next.base());
            if ((baseOwner != null && !oldIds.contains(baseOwner)) || (partOwner != null && !oldIds.contains(partOwner)))
                throw new IllegalArgumentException("Replacement base occupied");
            collectHarvests(newHarvests, next.harvests());
            collectHarvests(newReproduction, next.reproduction());
            var nextParts = proposal.structures().get(next.id());
            if (nextParts == null) throw new IllegalArgumentException("Missing replacement structure");
            for (BlockPos pos : nextParts.keySet()) {
                if (!oldParts.contains(pos) || newOwners.putIfAbsent(pos, next.id()) != null)
                    throw new IllegalArgumentException("Structure is new or shared between replacements");
            }
        }
        if (!proposal.structures().keySet().equals(newIds)) throw new IllegalArgumentException("Extra structure records");
        for (var next : proposal.replacements()) {
            UUID owner = newOwners.get(next.base());
            if (owner != null && !owner.equals(next.id())) throw new IllegalArgumentException("Base overlaps another part");
        }
        collectHarvests(newHarvests, proposal.retiredHarvests());
        if (!oldHarvests.equals(newHarvests)) throw new IllegalArgumentException("Resource inheritance is not conserved");
        collectHarvests(newReproduction, proposal.retiredReproduction());
        if (!oldReproduction.equals(newReproduction))
            throw new IllegalArgumentException("Reproduction inheritance is not conserved");
        // All checks completed: the following operations cannot invoke species/world callbacks.
        for (var old : proposal.expected()) {
            populationIndex.remove(old);
            growth.cancel(old.id());
            individuals.remove(old.id());
            byBase.remove(old.base());
            Map<BlockPos, String> owned = parts.remove(old.id());
            if (owned != null) owned.keySet().forEach(pos -> partOwners.remove(pos, old.id()));
        }
        for (var next : proposal.replacements()) {
            populationIndex.add(next);
            individuals.put(next.id(), next);
            byBase.put(next.base(), next.id());
            Map<BlockPos, String> owned = new HashMap<>(proposal.structures().get(next.id()));
            if (!owned.isEmpty()) parts.put(next.id(), owned);
            owned.keySet().forEach(pos -> partOwners.put(pos, next.id()));
            growth.update(next);
        }
        setDirty();
        return true;
    }

    /** Shared by proposal builders and the final store validation; never converts mixed origins. */
    static void requireMergedRunnerBudget(java.util.List<PlantIndividualState> originals,
            RunnerGrowthAdmission.Budget budget) {
        int length = 0;
        int extra = 0;
        int chances = 0;
        for (var original : originals) {
            var old = original.runnerBudget().orElseThrow();
            length = Math.max(length, old.maximumLength());
            extra = Math.max(extra, old.extraLength());
            chances = Math.max(chances, old.branchChances());
        }
        if (budget.maximumLength() > length || budget.extraLength() > extra || budget.branchChances() > chances)
            throw new IllegalArgumentException("Merging cannot accumulate runner allowances");
    }

    /** Shared by proposal builders and the final store validation; never converts mixed origins. */
    static void requireCompatible(PlantIndividualState first, PlantIndividualState other) {
        if (!first.species().equals(other.species()) || !first.stage().equals(other.stage())
                || first.source() != other.source() || first.origin() != other.origin()
                || !first.population().equals(other.population())
                || first.canBePruned() != other.canBePruned() || first.pruned() != other.pruned()
                || !first.environmentReasons().equals(other.environmentReasons())
                || first.runnerBudget().isPresent() != other.runnerBudget().isPresent())
            throw new IllegalArgumentException("Split/merge needs an explicit policy for different states");
    }

    private static void collectHarvests(Map<String, Integer> target, Map<String, Integer> values) {
        values.forEach((key, count) -> {
            if (key == null || key.isBlank() || count == null || count < 0 || target.putIfAbsent(key, count) != null)
                throw new IllegalArgumentException("Invalid or duplicated resource key");
        });
    }

    private static PlantIndividuals load(CompoundTag tag, HolderLookup.Provider registries) {
        PlantIndividuals data = new PlantIndividuals();
        try {
            int version = tag.getInt("Version");
            if (!tag.contains("Version", Tag.TAG_INT) || version < 1 || version > VERSION
                    || !tag.contains("Individuals", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported plant data version");
            ListTag entries = tag.getList("Individuals", Tag.TAG_COMPOUND);
            if (entries.size() != ((ListTag) tag.get("Individuals")).size())
                throw new IllegalArgumentException("Invalid individual list type");
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                for (String key : new String[]{"Species", "Stage", "Source", "Population"})
                    if (!entry.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing " + key);
                for (String key : new String[]{"Base", "TargetTicks", "ElapsedTicks"})
                    if (!entry.contains(key, Tag.TAG_LONG)) throw new IllegalArgumentException("Missing " + key);
                if (!entry.hasUUID("Id") || !entry.contains("Harvests", Tag.TAG_COMPOUND)
                        || !entry.contains("EnvironmentReasons", Tag.TAG_LIST)
                        || !entry.contains("CanBePruned", Tag.TAG_BYTE) || !entry.contains("Pruned", Tag.TAG_BYTE))
                    throw new IllegalArgumentException("Incomplete individual");
                Map<String, Integer> harvests = new HashMap<>();
                CompoundTag quotas = entry.getCompound("Harvests");
                for (String key : quotas.getAllKeys()) {
                    if (!quotas.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Invalid quota");
                    harvests.put(key, quotas.getInt(key));
                }
                HashSet<String> reasons = new HashSet<>();
                Map<String, Integer> reproduction = new HashMap<>();
                if (version >= 4) {
                    if (!entry.contains("Reproduction", Tag.TAG_COMPOUND))
                        throw new IllegalArgumentException("Missing reproduction budgets");
                    CompoundTag budgets = entry.getCompound("Reproduction");
                    for (String key : budgets.getAllKeys()) {
                        if (!budgets.contains(key, Tag.TAG_INT))
                            throw new IllegalArgumentException("Invalid reproduction budget");
                        reproduction.put(key, budgets.getInt(key));
                    }
                }
                ListTag savedReasons = entry.getList("EnvironmentReasons", Tag.TAG_STRING);
                if (savedReasons.size() != ((ListTag) entry.get("EnvironmentReasons")).size())
                    throw new IllegalArgumentException("Invalid environment reason list");
                for (int j = 0; j < savedReasons.size(); j++) reasons.add(savedReasons.getString(j));
                if (version >= 3 && !entry.contains("Origin", Tag.TAG_STRING))
                    throw new IllegalArgumentException("Missing origin");
                Optional<RunnerGrowthAdmission.Budget> runnerBudget = Optional.empty();
                if (version >= 5) {
                    if (!entry.contains("HasRunnerBudget", Tag.TAG_BYTE))
                        throw new IllegalArgumentException("Missing runner budget presence flag");
                    if (entry.getBoolean("HasRunnerBudget")) {
                        if (!entry.contains("RunnerBudget", Tag.TAG_COMPOUND))
                            throw new IllegalArgumentException("Missing runner budget");
                        CompoundTag budget = entry.getCompound("RunnerBudget");
                        for (String key : new String[]{"BaseLength", "ExtraLength", "BranchChances"})
                            if (!budget.contains(key, Tag.TAG_INT)) throw new IllegalArgumentException("Missing " + key);
                        runnerBudget = Optional.of(new RunnerGrowthAdmission.Budget(budget.getInt("BaseLength"),
                                budget.getInt("ExtraLength"), budget.getInt("BranchChances")));
                    } else if (entry.contains("RunnerBudget")) {
                        throw new IllegalArgumentException("Unexpected runner budget");
                    }
                }
                PlantIndividualState state = new PlantIndividualState(entry.getUUID("Id"),
                        ResourceLocation.parse(entry.getString("Species")), BlockPos.of(entry.getLong("Base")),
                        PlantSource.valueOf(entry.getString("Source")), entry.getString("Stage"),
                        entry.getString("Population"), entry.getBoolean("CanBePruned"), entry.getBoolean("Pruned"),
                        entry.getLong("TargetTicks"), entry.getLong("ElapsedTicks"), reasons, harvests,
                        version >= 3 ? PlantOrigin.valueOf(entry.getString("Origin")) : PlantOrigin.UNKNOWN,
                        reproduction, runnerBudget);
                if (data.individuals.putIfAbsent(state.id(), state) != null
                        || data.byBase.putIfAbsent(state.base(), state.id()) != null)
                    throw new IllegalArgumentException("Duplicate individual or base");
            }
            // Load identities first so cross-individual anchor collisions are detected regardless of order.
            if (version >= 2) {
                for (int i = 0; i < entries.size(); i++) {
                    CompoundTag entry = entries.getCompound(i);
                    if (!entry.contains("Parts", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing parts");
                    ListTag savedParts = entry.getList("Parts", Tag.TAG_COMPOUND);
                    if (savedParts.size() != ((ListTag) entry.get("Parts")).size())
                        throw new IllegalArgumentException("Invalid parts list");
                    Map<BlockPos, String> restored = new HashMap<>();
                    for (int j = 0; j < savedParts.size(); j++) {
                        CompoundTag part = savedParts.getCompound(j);
                        if (!part.contains("Position", Tag.TAG_LONG) || !part.contains("Role", Tag.TAG_STRING))
                            throw new IllegalArgumentException("Incomplete part");
                        if (restored.putIfAbsent(BlockPos.of(part.getLong("Position")), part.getString("Role")) != null)
                            throw new IllegalArgumentException("Duplicate part position");
                    }
                    data.attachParts(entry.getUUID("Id"), restored);
                }
            }
            // V1 has no structural positions: never infer them by scanning or claim adjacent blocks.
            data.setDirty(version < VERSION);
            data.individuals.values().forEach(data.growth::update);
            data.individuals.values().forEach(data.populationIndex::add);
        } catch (IllegalArgumentException exception) {
            // Preserve the entire original payload rather than silently refreshing resources.
            data.individuals.clear();
            data.byBase.clear();
            data.parts.clear();
            data.partOwners.clear();
            data.setDirty(false);
            data.preservedUnsupportedData = tag.copy();
            org.slf4j.LoggerFactory.getLogger(PlantIndividuals.class)
                    .error("Plant individual data opened read-only; original payload preserved", exception);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (isReadOnly()) return preservedUnsupportedData.copy();
        tag.putInt("Version", VERSION);
        ListTag entries = new ListTag();
        for (PlantIndividualState stored : individuals.values()) {
            PlantIndividualState state = growth.current(stored);
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", state.id());
            entry.putString("Species", state.species().toString());
            entry.putLong("Base", state.base().asLong());
            entry.putString("Source", state.source().name());
            entry.putString("Origin", state.origin().name());
            entry.putString("Stage", state.stage());
            entry.putString("Population", state.population());
            entry.putBoolean("CanBePruned", state.canBePruned());
            entry.putBoolean("Pruned", state.pruned());
            entry.putLong("TargetTicks", state.targetTicks());
            entry.putLong("ElapsedTicks", state.elapsedTicks());
            ListTag reasons = new ListTag();
            state.environmentReasons().stream().sorted().forEach(reason -> reasons.add(StringTag.valueOf(reason)));
            entry.put("EnvironmentReasons", reasons);
            CompoundTag quotas = new CompoundTag();
            state.harvests().forEach(quotas::putInt);
            entry.put("Harvests", quotas);
            CompoundTag reproduction = new CompoundTag();
            state.reproduction().forEach(reproduction::putInt);
            entry.put("Reproduction", reproduction);
            entry.putBoolean("HasRunnerBudget", state.runnerBudget().isPresent());
            state.runnerBudget().ifPresent(budget -> {
                CompoundTag savedBudget = new CompoundTag();
                savedBudget.putInt("BaseLength", budget.baseLength());
                savedBudget.putInt("ExtraLength", budget.extraLength());
                savedBudget.putInt("BranchChances", budget.branchChances());
                entry.put("RunnerBudget", savedBudget);
            });
            ListTag savedParts = new ListTag();
            for (var part : parts.getOrDefault(state.id(), Map.of()).entrySet()) {
                CompoundTag saved = new CompoundTag();
                saved.putLong("Position", part.getKey().asLong());
                saved.putString("Role", part.getValue());
                savedParts.add(saved);
            }
            entry.put("Parts", savedParts);
            entries.add(entry);
        }
        tag.put("Individuals", entries);
        return tag;
    }
}
