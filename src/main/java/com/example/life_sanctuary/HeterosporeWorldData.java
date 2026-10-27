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
    private final PlantGrowthScheduler scheduler = new PlantGrowthScheduler();
    private final GametophyteDonorIndex index = new GametophyteDonorIndex();
    private final HeterosporeTransitionScope transitionScope = new HeterosporeTransitionScope();
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
                || !store.find(expected.id()).filter(expected::equals).isPresent()) return Optional.empty();
        var adapter = HeterosporeAdapters.find(expected.species());
        if (adapter != null && adapter.gametophyteStage().equals(expected.stage())) return Optional.empty();
        if (sexes.containsKey(expected.id())) return sex(expected);
        var sex = PlantSporeTraits.sampleSex(traits, traits.allMonoecious() ? 0 : level.random.nextInt(100));
        sexes.put(expected.id(), new SexEntry(new Identity(expected.species(), expected.base()), sex)); setDirty(); return Optional.of(sex);
    }
    /** The real bag effect must place/register the individual first; neither this method nor the item guesses blocks. */
    public boolean registerGametophyte(ServerLevel level, PlantIndividualState expected, HeterosporeGametophyteRules.Kind kind) {
        var store = PlantIndividuals.get(level); var adapter = HeterosporeAdapters.find(expected.species());
        var form = switch (kind) { case FEMALE -> SporeBagContents.Form.MEGA; case MALE -> SporeBagContents.Form.MICRO; case MIXED -> SporeBagContents.Form.MIXED; };
        if (preserved != null || store.isReadOnly() || store.isApplyingStructure() || adapter == null
                || !adapter.gametophyteStage().equals(expected.stage()) || !expected.source().canFinishCurrentCycle()
                || expected.targetTicks() != 0 || expected.elapsedTicks() != 0 || !PlantSporeTraits.find(expected.species()).differentiatedSpores()
                || !SporeBagContents.allowed(PlantSporeTraits.find(expected.species()), form)
                || !store.find(expected.id()).filter(expected::equals).isPresent() || cycles.containsKey(expected.id()) || sexes.containsKey(expected.id())) return false;
        var cycle = HeterosporeCycle.start(kind, level.random);
        cycles.put(expected.id(), new Entry(new Identity(expected.species(), expected.base()), expected.stage(), cycle, cycle.remaining(), 0, false));
        reconcile(level, expected.id(), false); setDirty(); return true;
    }
    public Optional<HeterosporeCycle> cycle(UUID id) {
        var entry = cycles.get(id); return preserved == null && entry != null ? Optional.of(current(id, entry)) : Optional.empty();
    }
    public boolean frozen(UUID id) { var entry = cycles.get(id); return preserved != null || entry != null && entry.frozen(); }
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
                || !PlantSporeTraits.find(owner.species()).differentiatedSpores() || HeterosporeAdapters.find(owner.species()) == null || !runnable(owner)) return;
        scheduler.updateSystem(id, owner.base(), cycle.remaining() == 0 ? 100 : cycle.remaining(), 0);
        index.update(id, owner.species(), owner.base(), cycle.provides());
    }
    /** No world writes here: this can run inside a shared structure commit. */
    public void changed(ServerLevel level, PlantIndividualState before, PlantIndividualState next) {
        if (preserved != null) return;
        var entry = cycles.get(next.id()); if (entry == null) return;
        if (!entry.identity().matches(next) || !entry.stage().equals(next.stage())) { removeCycle(next.id()); return; }
        if (before.pruned() != next.pruned() || before.source() != next.source() || !before.environmentReasons().equals(next.environmentReasons())) {
            reconcile(level, next.id(), !before.pruned() && next.pruned()); setDirty();
        }
    }
    public void remove(UUID id) { if (preserved != null) return; removeCycle(id); if (sexes.remove(id) != null) setDirty(); }
    private void removeCycle(UUID id) { scheduler.cancel(id); index.remove(id); if (cycles.remove(id) != null) setDirty(); }
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
                && current(expected.id(), entry).action() == action && store.find(expected.id()).filter(expected::equals).isPresent();
    }
    private HeterosporeGametophyteRules.Candidate candidate(ServerLevel level, UUID id) {
        var entry = cycles.get(id); if (entry == null || entry.frozen()) return null;
        var owner = PlantIndividuals.get(level).find(id).orElse(null);
        if (owner == null || !entry.identity().matches(owner) || !entry.stage().equals(owner.stage()) || !runnable(owner)
                || !level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(owner.base().getX() >> 4, owner.base().getZ() >> 4))) return null;
        var cycle = current(id, entry);
        return new HeterosporeGametophyteRules.Candidate(id, owner.species(), owner.population(), owner.base(), cycle.kind(), cycle.mature(), true);
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
                        removeCycle(id);
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
                if (!saved.contains("Sex", Tag.TAG_STRING) || data.sexes.putIfAbsent(id, new SexEntry(identity, PlantSporeTraits.Sex.valueOf(saved.getString("Sex")))) != null)
                    throw new IllegalArgumentException("Invalid sex record");
            }
            var bases = new java.util.HashSet<BlockPos>();
            for (var value : list(tag, "Cycles")) {
                var saved = (CompoundTag) value; var id = uuid(saved); var identity = identity(saved);
                if (!saved.contains("Stage", Tag.TAG_STRING) || !saved.contains("Cycle", Tag.TAG_COMPOUND) || !saved.contains("Wake", Tag.TAG_LONG)
                        || !saved.contains("Elapsed", Tag.TAG_LONG) || !saved.contains("Frozen", Tag.TAG_BYTE)) throw new IllegalArgumentException("Incomplete cycle record");
                var cycle = HeterosporeCycle.read(saved.getCompound("Cycle"));
                var entry = new Entry(identity, saved.getString("Stage"), cycle, saved.getLong("Wake"), saved.getLong("Elapsed"), saved.getBoolean("Frozen"));
                if (entry.wake() != wake(cycle) || !bases.add(identity.base()) || data.cycles.putIfAbsent(id, entry) != null)
                    throw new IllegalArgumentException("Conflicting cycle");
            }
        } catch (RuntimeException failure) { data.sexes.clear(); data.cycles.clear(); data.preserved = tag.copy(); }
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
