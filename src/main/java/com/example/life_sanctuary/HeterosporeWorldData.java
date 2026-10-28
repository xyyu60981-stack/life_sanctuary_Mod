package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension biological state, with derived clocks/indexes. No duplicate plant identity or population ledger. */
public final class HeterosporeWorldData extends SavedData {
    private static final Factory<HeterosporeWorldData> FACTORY = new Factory<>(HeterosporeWorldData::new, HeterosporeWorldData::load);
    private record Identity(ResourceLocation species, BlockPos base) {
        Identity { java.util.Objects.requireNonNull(species); base = base.immutable(); }
        boolean matches(PlantIndividualState plant) { return species.equals(plant.species()) && base.equals(plant.base()); }
    }
    private record SexEntry(Identity identity, PlantSporeTraits.Sex sex) {}
    private record Entry(Identity identity, String stage, HeterosporeCycle cycle, long wake, long elapsed, boolean frozen) {
        Entry {
            if (stage == null || stage.isBlank() || wake < 1 || elapsed < 0 || elapsed > wake) throw new IllegalArgumentException("Invalid runtime cycle");
        }
    }
    private final Map<UUID, SexEntry> sexes = new HashMap<>();
    private final Map<UUID, Entry> cycles = new HashMap<>();
    /** Prevent an orphaned saved identity from being silently replaced at the same base. */
    private final Map<BlockPos, UUID> occupiedBases = new HashMap<>();
    private final PlantGrowthScheduler scheduler = new PlantGrowthScheduler();
    private final GametophyteDonorIndex index = new GametophyteDonorIndex();
    private final HeterosporeTransitionScope transitionScope = new HeterosporeTransitionScope();
    /** In-memory proof of this exact pending birth. Reloading a frozen record cannot recreate the proof. */
    static final class BirthReservation {
        private final UUID id;
        private final Entry pending;
        private BirthReservation(UUID id, Entry pending) { this.id = id; this.pending = pending; }
    }
    private CompoundTag preserved;
    private boolean bound;
    public static HeterosporeWorldData get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Biology data needs the server thread");
        var data = level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_heterospory");
        if (!data.bound && data.preserved == null) {
            data.bound = true;
            for (var id : List.copyOf(data.cycles.keySet())) data.reconcile(level, id, false);
        }
        return data;
    }
    public boolean isReadOnly() { return preserved != null; }
    boolean containsIdentity(UUID id) { return cycles.containsKey(id) || sexes.containsKey(id); }
    boolean containsBase(BlockPos pos) { return occupiedBases.containsKey(pos); }
    public Optional<PlantSporeTraits.Sex> sex(PlantIndividualState plant) {
        var entry = sexes.get(plant.id());
        return preserved == null && entry != null && entry.identity().matches(plant)
                && (!PlantSporeTraits.find(plant.species()).allMonoecious() || entry.sex() == PlantSporeTraits.Sex.MONOECIOUS)
                ? Optional.of(entry.sex()) : Optional.empty();
    }
    /** Called once for a newly initialized sporophyte; existing labels are never rerolled on reads or reloads. */
    public Optional<PlantSporeTraits.Sex> assignSex(ServerLevel level, PlantIndividualState expected) {
        var store = PlantIndividuals.get(level); var traits = PlantSporeTraits.find(expected.species());
        if (preserved != null || store.isReadOnly() || store.isApplyingStructure() || cycles.containsKey(expected.id()) || !traits.differentiatedSpores()
                || occupiedByOther(expected.id(), expected.base())
                || !HeterosporePopulation.matches(expected)
                || !store.find(expected.id()).filter(expected::equals).isPresent()) return Optional.empty();
        var adapter = HeterosporeAdapters.find(expected.species());
        if (adapter != null && adapter.gametophyteStage().equals(expected.stage())) return Optional.empty();
        if (sexes.containsKey(expected.id())) return sex(expected);
        var sex = PlantSporeTraits.sampleSex(traits, traits.allMonoecious() ? 0 : level.random.nextInt(100));
        sexes.put(expected.id(), new SexEntry(new Identity(expected.species(), expected.base()), sex));
        occupiedBases.put(expected.base().immutable(), expected.id()); setDirty(); return Optional.of(sex);
    }
    /** The real bag effect must place/register the individual first; neither this method nor the item guesses blocks. */
    public boolean registerGametophyte(ServerLevel level, PlantIndividualState expected, HeterosporeGametophyteRules.Kind kind) {
        var store = PlantIndividuals.get(level); var adapter = HeterosporeAdapters.find(expected.species());
        var form = switch (kind) { case FEMALE -> SporeBagContents.Form.MEGA; case MALE -> SporeBagContents.Form.MICRO; case MIXED -> SporeBagContents.Form.MIXED; };
        if (preserved != null || store.isReadOnly() || store.isApplyingStructure() || adapter == null
                || !adapter.gametophyteStage().equals(expected.stage()) || !expected.source().canFinishCurrentCycle()
                || expected.targetTicks() != 0 || expected.elapsedTicks() != 0 || !PlantSporeTraits.find(expected.species()).differentiatedSpores()
                || !SporeBagContents.allowed(PlantSporeTraits.find(expected.species()), form)
                || !store.find(expected.id()).filter(expected::equals).isPresent() || cycles.containsKey(expected.id()) || sexes.containsKey(expected.id())
                || !store.partsOf(expected.id()).containsKey(expected.base()) || !HeterosporePopulation.matches(expected)
                || occupiedBases.containsKey(expected.base())) return false;
        var cycle = HeterosporeCycle.start(kind, level.random);
        cycles.put(expected.id(), new Entry(new Identity(expected.species(), expected.base()), expected.stage(), cycle, cycle.remaining(), 0, false));
        occupiedBases.put(expected.base().immutable(), expected.id());
        reconcile(level, expected.id(), false); setDirty(); return true;
    }
    /** Reserve only for a freshly registered, structure-free identity, before invoking any world writes. */
    BirthReservation reserveBirth(ServerLevel level, PlantIndividualState expected, HeterosporeGametophyteRules.Kind kind,
            HeterosporeCycle cultivated) {
        var store = PlantIndividuals.get(level);
        if (preserved != null || store.isReadOnly() || store.isApplyingStructure()
                || !(cultivated == null ? HeterosporeBirths.validIndividual(expected, kind) : HeterosporeBirths.validCultivated(expected, cultivated))
                || !store.find(expected.id()).filter(expected::equals).isPresent()
                || !store.partsOf(expected.id()).isEmpty() || cycles.containsKey(expected.id()) || sexes.containsKey(expected.id())
                || occupiedBases.containsKey(expected.base())) return null;
        var cycle = cultivated == null ? HeterosporeCycle.start(kind, level.random) : cultivated;
        var entry = new Entry(new Identity(expected.species(), expected.base()), expected.stage(), cycle, wake(cycle), 0, true);
        cycles.put(expected.id(), entry); setDirty();
        occupiedBases.put(expected.base().immutable(), expected.id());
        return new BirthReservation(expected.id(), entry);
    }
    /** Activate once, only after the shared committer has returned success with exactly these owned parts. */
    boolean activateBirth(ServerLevel level, PlantIndividualState expected, BirthReservation proof, Map<BlockPos, String> parts) {
        var store = PlantIndividuals.get(level);
        if (preserved != null || store.isReadOnly() || store.isApplyingStructure() || proof == null || !proof.id.equals(expected.id())
                || cycles.get(proof.id) != proof.pending || !store.find(expected.id()).filter(expected::equals).isPresent()
                || parts.isEmpty() || !parts.containsKey(expected.base()) || !store.partsOf(expected.id()).equals(parts)) return false;
        var pending = proof.pending;
        cycles.put(proof.id, new Entry(pending.identity(), pending.stage(), pending.cycle(), pending.wake(), 0, false));
        reconcile(level, proof.id, false); setDirty(); return true;
    }
    public Optional<HeterosporeCycle> cycle(UUID id) {
        var entry = cycles.get(id); return preserved == null && entry != null ? Optional.of(current(id, entry)) : Optional.empty();
    }
    public boolean frozen(UUID id) { var entry = cycles.get(id); return preserved != null || entry != null && entry.frozen(); }
    /** Pause the auxiliary timeline independently when an external inventory transaction is uncertain. */
    void freezeForRecovery(PlantIndividualState expected) {
        if (preserved != null) return;
        var entry = cycles.get(expected.id());
        if (entry == null || !entry.identity().matches(expected) || !entry.stage().equals(expected.stage())) return;
        var stopped = current(expected.id(), entry);
        scheduler.cancel(expected.id()); index.remove(expected.id());
        cycles.put(expected.id(), new Entry(entry.identity(), entry.stage(), stopped, wake(stopped), 0, true));
        setDirty();
    }
    private HeterosporeCycle current(UUID id, Entry entry) {
        long elapsed = scheduler.currentElapsed(id, entry.elapsed());
        return entry.cycle().advance(Math.min(elapsed, entry.cycle().remaining()));
    }
    private static boolean runnable(PlantIndividualState plant) { return plant.source().canFinishCurrentCycle() && !plant.pruned() && plant.environmentReasons().isEmpty(); }
    private void reconcile(ServerLevel level, UUID id, boolean reset) {
        var entry = cycles.get(id); if (entry == null) return;
        var store = PlantIndividuals.get(level); var owner = store.find(id).orElse(null);
        var cycle = current(id, entry); scheduler.cancel(id); index.remove(id);
        if (reset) cycle = cycle.pruned();
        var normalized = new Entry(entry.identity(), entry.stage(), cycle, wake(cycle), 0, entry.frozen());
        cycles.put(id, normalized);
        if (store.isReadOnly() || owner == null || !entry.identity().matches(owner) || !entry.stage().equals(owner.stage()) || entry.frozen()
                || !PlantSporeTraits.find(owner.species()).differentiatedSpores() || HeterosporeAdapters.find(owner.species()) == null
                || HeterosporePopulation.at(owner.species(), owner.base()).isEmpty() || !runnable(owner)) return;
        scheduler.updateSystem(id, owner.base(), cycle.remaining() == 0 ? 100 : cycle.remaining(), 0);
        index.update(id, owner.species(), owner.base(), cycle.provides());
    }
    /** No world writes here: this can run inside a shared structure commit. */
    public void changed(ServerLevel level, PlantIndividualState before, PlantIndividualState next) {
        if (preserved != null) return;
        var entry = cycles.get(next.id()); if (entry == null) return;
        if (!entry.identity().matches(next) || !entry.stage().equals(next.stage())) {
            // Keep the old cycle until ready() validates the result of this exact terminal callback.
            // A dishonest/partial adapter must leave a frozen record for diagnosis.
            if (entry.identity().matches(before) && entry.stage().equals(before.stage())
                    && transitionScope.matches(before, HeterosporeCycle.Action.SEEDLING)) return;
            removeCycle(next.id()); return;
        }
        if (before.pruned() != next.pruned() || before.source() != next.source() || !before.environmentReasons().equals(next.environmentReasons())) {
            reconcile(level, next.id(), !before.pruned() && next.pruned()); setDirty();
        }
    }
    public void remove(UUID id) {
        if (preserved != null) return;
        removeCycle(id);
        var sex = sexes.remove(id);
        if (sex != null) { occupiedBases.remove(sex.identity().base(), id); setDirty(); }
    }
    private void removeCycle(UUID id) {
        scheduler.cancel(id); index.remove(id);
        var cycle = cycles.remove(id);
        if (cycle != null) { occupiedBases.remove(cycle.identity().base(), id); setDirty(); }
    }
    private boolean occupiedByOther(UUID id, BlockPos base) {
        var existing = occupiedBases.get(base);
        return existing != null && !existing.equals(id);
    }
    public void tick(ServerLevel level) { if (preserved == null) scheduler.tickSystems(level, this::setDirty, id -> ready(level, id)); }
    /** A due timestamp alone grants no authority: only this owner's active terminal callback does. */
    boolean authorizes(ServerLevel level, PlantIndividualState expected, HeterosporeCycle.Action action) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
        var entry = cycles.get(expected.id());
        var adapter = HeterosporeAdapters.find(expected.species());
        var store = PlantIndividuals.get(level);
        return preserved == null && !store.isReadOnly() && entry != null && !entry.frozen()
                && transitionScope.matches(expected, action) && entry.identity().matches(expected)
                && entry.stage().equals(expected.stage()) && adapter != null && adapter.gametophyteStage().equals(expected.stage())
                && expected.targetTicks() == 0 && expected.elapsedTicks() == 0 && runnable(expected)
                && HeterosporePopulation.at(expected.species(), expected.base()).isPresent()
                && current(expected.id(), entry).action() == action && store.find(expected.id()).filter(expected::equals).isPresent();
    }
    private HeterosporeGametophyteRules.Candidate candidate(ServerLevel level, UUID id) {
        var entry = cycles.get(id); if (entry == null || entry.frozen()) return null;
        var owner = PlantIndividuals.get(level).find(id).orElse(null);
        if (owner == null || !entry.identity().matches(owner) || !entry.stage().equals(owner.stage()) || !runnable(owner)
                || !level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(owner.base().getX() >> 4, owner.base().getZ() >> 4))) return null;
        var cycle = current(id, entry);
        var population = HeterosporePopulation.at(owner.species(), owner.base()).orElse(null);
        return population == null ? null : new HeterosporeGametophyteRules.Candidate(
                id, owner.species(), population, owner.base(), cycle.kind(), cycle.mature(), true);
    }
    private void ready(ServerLevel level, UUID id) {
        var entry = cycles.get(id); if (entry == null) { scheduler.cancel(id); return; }
        var store = PlantIndividuals.get(level); if (store.isReadOnly() || store.isApplyingStructure()) return;
        var owner = store.find(id).orElse(null);
        if (owner == null || !entry.identity().matches(owner) || !entry.stage().equals(owner.stage())) { removeCycle(id); return; }
        if (!runnable(owner)) { reconcile(level, id, false); return; }
        var cycle = current(id, entry);
        cycles.put(id, new Entry(entry.identity(), entry.stage(), cycle, 100, 0, false)); scheduler.cancel(id);
        try {
            switch (cycle.action()) {
                case MATURE -> install(level, id, entry, cycle.finishMaturity(level.random));
                case SEARCH -> {
                    var receiver = candidate(level, id); if (receiver == null) throw new IllegalStateException("Missing receiver");
                    var nearby = new java.util.ArrayList<HeterosporeGametophyteRules.Candidate>();
                    for (var donorId : index.nearby(owner.species(), owner.base())) {
                        var donorEntry = cycles.get(donorId);
                        var donor = candidate(level, donorId);
                        if (donor != null && donorEntry != null && current(donorId, donorEntry).provides()) nearby.add(donor);
                    }
                    int donors = HeterosporeGametophyteRules.donors(receiver, nearby, cycle.attempts() + 1).size();
                    int percent = HeterosporeGametophyteRules.successPercent(donors, cycle.attempts() + 1);
                    install(level, id, entry, cycle.searchResult(percent == 100 || percent > 0 && level.random.nextInt(100) < percent, level.random));
                }
                case SEEDLING, DISAPPEAR -> {
                    var adapter = HeterosporeAdapters.find(owner.species()); if (adapter == null) throw new IllegalStateException("Missing species adapter");
                    var result = transitionScope.run(owner, cycle.action(), () -> cycle.action() == HeterosporeCycle.Action.SEEDLING
                            ? adapter.change().grow(level, owner) : adapter.change().disappear(level, owner));
                    if (result == HeterosporeAdapters.Result.RECOVERY_REQUIRED) throw new IllegalStateException("World transition requires recovery");
                    if (result == HeterosporeAdapters.Result.APPLIED) {
                        var actual = store.find(id).orElse(null);
                        boolean valid = cycle.action() == HeterosporeCycle.Action.DISAPPEAR ? actual == null
                                : actual != null && entry.identity().matches(actual) && PlantSpeciesRegistry.find(owner.species()).orElseThrow()
                                    .requireTransition(entry.stage()).nextStage().equals(actual.stage());
                        if (!valid) throw new IllegalStateException("Adapter reported an uncommitted transition");
                        // The gametophyte record must be retired before assigning the same UUID to its sporophyte.
                        // This is independent of whether the structure committer already called changed().
                        removeCycle(id);
                        // A successfully established sporophyte gets one persistent whole-plant sex.
                        // Never sample during reads, on reload, or before the world commit succeeds.
                        if (cycle.action() == HeterosporeCycle.Action.SEEDLING && assignSex(level, actual).isEmpty()) {
                            PlantLifecycle.setPauseReason(store, id, "heterospore_sex_assignment_error", true);
                            throw new IllegalStateException("Committed seedling could not receive a persistent sex");
                        }
                    } else if (!store.find(id).filter(owner::equals).isPresent()) {
                        throw new IllegalStateException("Blocked adapter changed its owner");
                    } else if (cycles.containsKey(id)) {
                        scheduler.updateSystem(id, owner.base(), 100, 0); index.update(id, owner.species(), owner.base(), cycle.provides());
                    }
                }
                case NONE -> throw new IllegalStateException("Wake without a due event");
            }
            setDirty();
        } catch (RuntimeException failure) {
            var remaining = cycles.get(id);
            if (remaining != null) {
                var stopped = current(id, remaining);
                cycles.put(id, new Entry(remaining.identity(), remaining.stage(), stopped, wake(stopped), 0, true));
            }
            scheduler.cancel(id); index.remove(id); setDirty();
            LifeSanctuaryMod.LOGGER.error("Heterospore cycle failed for " + id, failure);
        }
    }
    private void install(ServerLevel level, UUID id, Entry identity, HeterosporeCycle cycle) {
        cycles.put(id, new Entry(identity.identity(), identity.stage(), cycle, wake(cycle), 0, false)); reconcile(level, id, false);
    }
    private static long wake(HeterosporeCycle cycle) { return cycle.remaining() == 0 ? 100 : cycle.remaining(); }
    public static HeterosporeWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new HeterosporeWorldData();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1) throw new IllegalArgumentException("Unknown biology version");
            for (var value : list(tag, "Sexes")) {
                var saved = (CompoundTag) value; var id = uuid(saved); var identity = identity(saved);
                if (!saved.contains("Sex", Tag.TAG_STRING) || data.occupiedBases.putIfAbsent(identity.base(), id) != null
                        || data.sexes.putIfAbsent(id, new SexEntry(identity, PlantSporeTraits.Sex.valueOf(saved.getString("Sex")))) != null)
                    throw new IllegalArgumentException("Invalid sex record");
            }
            for (var value : list(tag, "Cycles")) {
                var saved = (CompoundTag) value; var id = uuid(saved); var identity = identity(saved);
                if (!saved.contains("Stage", Tag.TAG_STRING) || !saved.contains("Cycle", Tag.TAG_COMPOUND) || !saved.contains("Wake", Tag.TAG_LONG)
                        || !saved.contains("Elapsed", Tag.TAG_LONG) || !saved.contains("Frozen", Tag.TAG_BYTE)) throw new IllegalArgumentException("Incomplete cycle record");
                var cycle = HeterosporeCycle.read(saved.getCompound("Cycle"));
                var entry = new Entry(identity, saved.getString("Stage"), cycle, saved.getLong("Wake"), saved.getLong("Elapsed"), saved.getBoolean("Frozen"));
                if (entry.wake() != wake(cycle) || data.sexes.containsKey(id) || data.occupiedBases.putIfAbsent(identity.base(), id) != null
                        || data.cycles.putIfAbsent(id, entry) != null)
                    throw new IllegalArgumentException("Conflicting cycle");
            }
        } catch (RuntimeException failure) { data.sexes.clear(); data.cycles.clear(); data.occupiedBases.clear(); data.preserved = tag.copy(); }
        return data;
    }
    private static ListTag list(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) throw new IllegalArgumentException("Missing list");
        var list = tag.getList(key, Tag.TAG_COMPOUND); if (list.size() != ((ListTag) tag.get(key)).size()) throw new IllegalArgumentException("Invalid list"); return list;
    }
    private static UUID uuid(CompoundTag tag) { if (!tag.hasUUID("Id")) throw new IllegalArgumentException("Missing identity"); return tag.getUUID("Id"); }
    private static Identity identity(CompoundTag tag) {
        if (!tag.contains("Species", Tag.TAG_STRING) || !tag.contains("Base", Tag.TAG_LONG)) throw new IllegalArgumentException("Missing species/base");
        return new Identity(ResourceLocation.parse(tag.getString("Species")), BlockPos.of(tag.getLong("Base")));
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return preserved.copy();
        tag.putInt("Version", 1); var savedSexes = new ListTag(); var savedCycles = new ListTag();
        sexes.forEach((id, entry) -> { var value = header(id, entry.identity()); value.putString("Sex", entry.sex().name()); savedSexes.add(value); });
        cycles.forEach((id, entry) -> { var value = header(id, entry.identity()); value.putString("Stage", entry.stage()); value.put("Cycle", entry.cycle().save());
            value.putLong("Wake", entry.wake()); value.putLong("Elapsed", scheduler.currentElapsed(id, entry.elapsed())); value.putBoolean("Frozen", entry.frozen()); savedCycles.add(value); });
        tag.put("Sexes", savedSexes); tag.put("Cycles", savedCycles); return tag;
    }
    private static CompoundTag header(UUID id, Identity identity) { var tag = new CompoundTag(); tag.putUUID("Id", id); tag.putString("Species", identity.species().toString()); tag.putLong("Base", identity.base().asLong()); return tag; }
}
