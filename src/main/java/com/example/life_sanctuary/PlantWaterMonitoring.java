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

    private static final class Entry {
        String group;
        boolean dry;
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

    /** Call on placement/stage change with the existing population, never a newly invented water population. */
    public void register(ServerLevel level, BlockPos pos, PlantSource source, PlantPopulationTag population) {
        remove(pos);
        if (source.isStatic() || !(level.getBlockState(pos).getBlock() instanceof WaterDependentPlant)) return;
        BlockPos key = pos.immutable();
        Entry e = new Entry(population.serializedName());
        entries.put(key, e);
        check(level, key, e, false);
        setDirty();
    }

    public void remove(BlockPos pos) {
        Entry e = entries.remove(pos);
        if (e == null) return;
        Set<BlockPos> group = wet.get(e.group);
        if (group != null) group.remove(pos);
        Set<BlockPos> pending = individuals.get(e.next);
        if (pending != null) pending.remove(pos);
        setDirty();
    }

    private void scheduleIndividual(BlockPos pos, Entry e, long now, long delay) {
        e.next = now + delay;
        individuals.computeIfAbsent(e.next, ignored -> new HashSet<>()).add(pos);
    }

    private void joinWet(BlockPos pos, Entry e, long now) {
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
        long now = level.getGameTime();
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            scheduleIndividual(pos, e, now, 1000);
            return; // No forced chunk loads and no decline charged during this skipped check.
        }
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof WaterDependentPlant plant)) { remove(pos); return; }
        var rule = plant.waterRequirement(state);
        Boolean water = findWater(level, plant.waterSearchCenter(pos, state), rule);
        if (water == null) { scheduleIndividual(pos, e, now, 1000); return; }
        if (water) { joinWet(pos, e, now); setDirty(); return; }
        if (!e.dry) { e.dry = true; e.remaining = rule.dryTicks(); }
        else if (elapsed) e.remaining = Math.max(0, e.remaining - Math.min(1000, e.remaining));
        if (e.remaining == 0 && plant.finishWaterDecline(level, pos, state)) { remove(pos); return; }
        scheduleIndividual(pos, e, now, Math.min(1000, Math.max(1, e.remaining)));
        setDirty();
    }

    /** null means the full search could not be completed without loading a chunk. */
    private static Boolean findWater(ServerLevel level, BlockPos pos, WaterDependentPlant.Requirement rule) {
        boolean incomplete = false;
        for (BlockPos candidate : BlockPos.betweenClosed(pos.offset(-rule.horizontalRadius(), -rule.verticalRadius(), -rule.horizontalRadius()),
                pos.offset(rule.horizontalRadius(), rule.verticalRadius(), rule.horizontalRadius()))) {
            if (!level.getChunkSource().hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) { incomplete = true; continue; }
            if (level.getFluidState(candidate).is(FluidTags.WATER)) return true;
        }
        return incomplete ? null : false;
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        while (!groups.isEmpty() && groups.firstKey() <= now) {
            for (String group : groups.pollFirstEntry().getValue()) {
                Set<BlockPos> batch = wet.remove(group);
                groupDue.remove(group);
                if (batch == null || batch.isEmpty()) continue;
                groupDue.put(group, now + 5000);
                groups.computeIfAbsent(now + 5000, ignored -> new HashSet<>()).add(group);
                for (BlockPos pos : batch) {
                    Entry e = entries.get(pos);
                    if (e != null && !e.dry) check(level, pos, e, false);
                }
            }
        }
        while (!individuals.isEmpty() && individuals.firstKey() <= now) {
            for (BlockPos pos : individuals.pollFirstEntry().getValue()) {
                Entry e = entries.get(pos);
                if (e != null && e.next <= now) check(level, pos, e, true);
            }
        }
    }

    private static PlantWaterMonitoring load(CompoundTag tag, HolderLookup.Provider registries) {
        PlantWaterMonitoring data = new PlantWaterMonitoring();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag saved = list.getCompound(i);
            BlockPos pos = BlockPos.of(saved.getLong("Position"));
            Entry e = new Entry(saved.getString("Group"));
            e.dry = saved.getBoolean("Dry");
            e.remaining = Math.max(0, saved.getLong("Remaining"));
            e.next = saved.getLong("Next");
            data.entries.put(pos, e);
            if (saved.getBoolean("Individual") || e.dry) {
                data.individuals.computeIfAbsent(e.next, ignored -> new HashSet<>()).add(pos);
            } else {
                data.wet.computeIfAbsent(e.group, ignored -> new HashSet<>()).add(pos);
                if (!data.groupDue.containsKey(e.group)) {
                    data.groupDue.put(e.group, e.next);
                    data.groups.computeIfAbsent(e.next, ignored -> new HashSet<>()).add(e.group);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        entries.forEach((pos, e) -> {
            CompoundTag saved = new CompoundTag();
            saved.putLong("Position", pos.asLong());
            saved.putString("Group", e.group);
            saved.putBoolean("Dry", e.dry);
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
