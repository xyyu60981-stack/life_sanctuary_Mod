package com.example.life_sanctuary;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension water groups. Queue lookup is constant work when no checks are due. */
public final class PlantWaterMonitoring extends SavedData {
    private static final Factory<PlantWaterMonitoring> FACTORY = new Factory<>(PlantWaterMonitoring::new, PlantWaterMonitoring::load);
    private final Map<BlockPos, Entry> entries = new HashMap<>();
    private final Map<String, Set<BlockPos>> wet = new HashMap<>();
    private final Map<String, Long> groupDue = new HashMap<>();
    private final TreeMap<Long, Set<String>> groups = new TreeMap<>();
    private final TreeMap<Long, Set<BlockPos>> individuals = new TreeMap<>();
    private CompoundTag preserved;
    private boolean ticked;
    private long lastTick;
    public boolean isReadOnly() { return preserved != null; }

    private static final class Entry {
        String group;
        boolean dry;
        boolean known;
        long remaining;
        long next;
        Entry(String group) { this.group = group; }
    }

    /** Read-only data for a future Jade server provider; this never scans the world. */
    public record Status(boolean lacksWater, long remainingTicks) {}

    public static PlantWaterMonitoring get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_plant_water");
    }

    public Status status(BlockPos pos) {
        Entry e = entries.get(pos);
        return e == null ? new Status(false, 0) : new Status(e.dry, e.remaining);
    }

    /** Missing monitoring data must not be displayed as confirmed sufficient water. */
    public Optional<Status> inspect(BlockPos pos) {
        Entry e = entries.get(pos);
        return preserved != null || e == null || !e.known ? Optional.empty() : Optional.of(new Status(e.dry, e.remaining));
    }

    /** Call on placement/stage change with the existing population, never a newly invented water population. */
    public void register(ServerLevel level, BlockPos pos, PlantSource source, PlantPopulationTag population) {
        register(level, pos, source, population.serializedName());
    }

    public void register(ServerLevel level, BlockPos pos, PlantSource source, String population) {
        if (source.isStatic() || population == null || population.isBlank()) { remove(pos); return; }
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WaterDependentPlant plant)
                || plant.waterRequirement(level, pos, state).isEmpty()) { remove(pos); return; }
        if (preserved != null) throw new IllegalStateException("Water monitoring save requires recovery");
        // Validate first: a failing species query must not erase an existing countdown.
        remove(pos);
        BlockPos key = pos.immutable();
        Entry e = new Entry(population);
        entries.put(key, e);
        check(level, key, e, false);
        setDirty();
    }

    public void remove(BlockPos pos) {
        if (preserved != null) return;
        Entry e = entries.remove(pos);
        if (e == null) return;
        leaveWet(pos, e);
        unscheduleIndividual(pos, e);
        setDirty();
    }

    private void unscheduleIndividual(BlockPos pos, Entry e) {
        Set<BlockPos> pending = individuals.get(e.next);
        if (pending != null) {
            pending.remove(pos);
            if (pending.isEmpty()) individuals.remove(e.next);
        }
        e.next = 0;
    }

    private void cancelGroup(String group) {
        Long due = groupDue.remove(group);
        if (due == null) return;
        var pending = groups.get(due);
        if (pending != null) {
            pending.remove(group);
            if (pending.isEmpty()) groups.remove(due);
        }
    }

    private void leaveWet(BlockPos pos, Entry e) {
        var members = wet.get(e.group);
        if (members != null && members.remove(pos) && members.isEmpty()) {
            wet.remove(e.group);
            cancelGroup(e.group);
        }
    }

    private void scheduleIndividual(BlockPos pos, Entry e, long now, long delay) {
        unscheduleIndividual(pos, e);
        leaveWet(pos, e);
        e.next = now + delay;
        individuals.computeIfAbsent(e.next, ignored -> new HashSet<>()).add(pos);
        setDirty();
    }

    private void joinWet(BlockPos pos, Entry e, long now) {
        unscheduleIndividual(pos, e);
        e.dry = false;
        e.remaining = 0;
        wet.computeIfAbsent(e.group, ignored -> new HashSet<>()).add(pos);
        if (!groupDue.containsKey(e.group)) {
            // Stable phase spreads populations over the full 5000-tick window.
            long due = now + 1 + Math.floorMod(e.group.hashCode() - now, 5000L);
            groupDue.put(e.group, due);
            groups.computeIfAbsent(due, ignored -> new HashSet<>()).add(e.group);
        }
    }

    private void check(ServerLevel level, BlockPos pos, Entry e, boolean elapsed) {
        if (entries.get(pos) != e) return;
        long now = level.getGameTime();
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            if (e.known) { e.known = false; setDirty(); }
            scheduleIndividual(pos, e, now, 1000);
            return; // No forced chunk loads and no decline charged during this skipped check.
        }
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WaterDependentPlant plant)) { remove(pos); return; }
        var rule = plant.waterRequirement(level, pos, state).orElse(null);
        if (entries.get(pos) != e) return;
        if (rule == null) { remove(pos); return; }
        Boolean water = findWater(level, plant.waterSearchCenter(pos, state), rule);
        if (water == null) {
            if (e.known) { e.known = false; setDirty(); }
            scheduleIndividual(pos, e, now, 1000); return;
        }
        boolean previouslyKnown = e.known;
        e.known = true;
        if (water) {
            plant.waterStatusChanged(level, pos, state, false);
            if (entries.get(pos) != e) return;
            joinWet(pos, e, now); setDirty(); return;
        }
        plant.waterStatusChanged(level, pos, state, true);
        if (entries.get(pos) != e) return;
        if (!e.dry) { e.dry = true; e.remaining = rule.dryTicks(); }
        else if (elapsed && previouslyKnown) e.remaining = Math.max(0, e.remaining - Math.min(1000, e.remaining));
        if (e.remaining == 0) {
            boolean finished = plant.finishWaterDecline(level, pos, state);
            if (entries.get(pos) != e) return;
            if (finished) { remove(pos); return; }
        }
        // A blocked terminal transaction must not turn a large farm into a per-tick retry loop.
        scheduleIndividual(pos, e, now, e.remaining == 0 ? 1000 : Math.min(1000, e.remaining));
        setDirty();
    }

    /** null means the full search could not be completed without loading a chunk. */
    private static Boolean findWater(ServerLevel level, BlockPos pos, WaterDependentPlant.Requirement rule) {
        boolean incomplete = false;
        int minX = pos.getX() - rule.horizontalRadius(), maxX = pos.getX() + rule.horizontalRadius();
        int minZ = pos.getZ() - rule.horizontalRadius(), maxZ = pos.getZ() + rule.horizontalRadius();
        int minY = Math.max(level.getMinBuildHeight(), pos.getY() - rule.verticalRadius());
        int maxY = Math.min(level.getMaxBuildHeight() - 1, pos.getY() + rule.verticalRadius());
        if (minY > maxY) return false;
        var candidate = new BlockPos.MutableBlockPos();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) { incomplete = true; continue; }
                int startX = Math.max(minX, chunkX << 4), endX = Math.min(maxX, (chunkX << 4) + 15);
                int startZ = Math.max(minZ, chunkZ << 4), endZ = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = startX; x <= endX; x++)
                    for (int z = startZ; z <= endZ; z++)
                        for (int y = minY; y <= maxY; y++) {
                            candidate.set(x, y, z);
                            if (chunk.getFluidState(candidate).is(FluidTags.WATER)) return true;
                        }
            }
        }
        return incomplete ? null : false;
    }

    public void tick(ServerLevel level) {
        if (preserved != null || !level.tickRateManager().runsNormally()) return;
        long now = level.getGameTime();
        if (ticked && lastTick == now) return;
        ticked = true;
        lastTick = now;
        while (!groups.isEmpty() && groups.firstKey() <= now) {
            for (String group : groups.pollFirstEntry().getValue()) {
                Set<BlockPos> batch = wet.remove(group);
                groupDue.remove(group);
                if (batch == null || batch.isEmpty()) continue;
                groupDue.put(group, now + 5000);
                groups.computeIfAbsent(now + 5000, ignored -> new HashSet<>()).add(group);
                for (BlockPos pos : batch) {
                    Entry e = entries.get(pos);
                    if (e != null && e.group.equals(group) && !e.dry) check(level, pos, e, false);
                }
                if (!wet.containsKey(group)) cancelGroup(group);
            }
        }
        while (!individuals.isEmpty() && individuals.firstKey() <= now) {
            var due = individuals.pollFirstEntry();
            for (BlockPos pos : due.getValue()) {
                Entry e = entries.get(pos);
                if (e != null && e.next == due.getKey()) check(level, pos, e, true);
            }
        }
    }

    private static PlantWaterMonitoring load(CompoundTag tag, HolderLookup.Provider registries) {
        PlantWaterMonitoring data = new PlantWaterMonitoring();
        try {
        if (tag.contains("Version") && (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1))
            throw new IllegalArgumentException("Unsupported water monitoring version");
        if (!tag.contains("Entries", Tag.TAG_LIST)) throw new IllegalArgumentException("Missing water entries");
        var raw = (ListTag) tag.get("Entries");
        if (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid water entry list");
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag saved = list.getCompound(i);
            if (!saved.contains("Position", Tag.TAG_LONG) || !saved.contains("Group", Tag.TAG_STRING)
                    || saved.getString("Group").isBlank() || !saved.contains("Dry", Tag.TAG_BYTE)
                    || !saved.contains("Remaining", Tag.TAG_LONG) || saved.getLong("Remaining") < 0
                    || !saved.contains("Next", Tag.TAG_LONG) || saved.getLong("Next") < 0
                    || !saved.contains("Individual", Tag.TAG_BYTE)
                    || saved.contains("Known") && !saved.contains("Known", Tag.TAG_BYTE))
                throw new IllegalArgumentException("Invalid water entry");
            BlockPos pos = BlockPos.of(saved.getLong("Position"));
            Entry e = new Entry(saved.getString("Group"));
            e.dry = saved.getBoolean("Dry");
            e.known = saved.contains("Known", Tag.TAG_BYTE) ? saved.getBoolean("Known") : e.dry;
            e.remaining = Math.max(0, saved.getLong("Remaining"));
            e.next = saved.getLong("Next");
            if (data.entries.putIfAbsent(pos, e) != null || !e.dry && e.remaining != 0)
                throw new IllegalArgumentException("Conflicting water entry");
            if (saved.getBoolean("Individual") || e.dry) {
                data.individuals.computeIfAbsent(e.next, ignored -> new HashSet<>()).add(pos);
            } else {
                data.wet.computeIfAbsent(e.group, ignored -> new HashSet<>()).add(pos);
                if (!data.groupDue.containsKey(e.group)) {
                    data.groupDue.put(e.group, e.next);
                    data.groups.computeIfAbsent(e.next, ignored -> new HashSet<>()).add(e.group);
                } else if (data.groupDue.get(e.group) != e.next) throw new IllegalArgumentException("Conflicting population deadline");
            }
        }
        } catch (RuntimeException failure) {
            data.entries.clear(); data.wet.clear(); data.groupDue.clear(); data.groups.clear(); data.individuals.clear();
            data.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(PlantWaterMonitoring.class)
                    .error("Water monitoring save preserved read-only: {}", failure.getMessage());
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (preserved != null) return tag.merge(preserved.copy());
        tag.putInt("Version", 1);
        ListTag list = new ListTag();
        entries.forEach((pos, e) -> {
            CompoundTag saved = new CompoundTag();
            saved.putLong("Position", pos.asLong());
            saved.putString("Group", e.group);
            saved.putBoolean("Dry", e.dry);
            saved.putBoolean("Known", e.known);
            saved.putLong("Remaining", e.remaining);
            boolean individual = !wet.getOrDefault(e.group, Set.of()).contains(pos);
            saved.putBoolean("Individual", individual);
            saved.putLong("Next", individual ? e.next : groupDue.getOrDefault(e.group, e.next));
            list.add(saved);
        });
        tag.put("Entries", list);
        return tag;
    }
}
