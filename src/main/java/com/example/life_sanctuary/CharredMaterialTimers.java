package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

/** Event-driven contact starts/cancels timers; only due tasks read neighbors. Never scan all charred blocks. */
public final class CharredMaterialTimers extends SavedData {
    private static final Factory<CharredMaterialTimers> FACTORY = new Factory<>(CharredMaterialTimers::new, CharredMaterialTimers::load);
    private record Entry(ResourceLocation block, CharredMaterialRules.Cycle cycle) {}
    private enum Contact { PRESENT, ABSENT, UNKNOWN }
    private final Map<BlockPos, Entry> entries = new HashMap<>();
    private final PlantGrowthScheduler queue = new PlantGrowthScheduler(32);
    private CompoundTag preserved;
    private BlockPos applying;
    public static CharredMaterialTimers get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_charred_materials");
    }
    private static UUID task(BlockPos pos) { return new UUID(0, pos.asLong()); }
    public boolean isReadOnly() { return preserved != null; }
    public Optional<CharredMaterialRules.Cycle> cycle(BlockPos pos) {
        var entry = entries.get(pos);
        return entry == null ? Optional.empty() : Optional.of(entry.cycle().advance(
                queue.currentElapsed(task(pos), entry.cycle().elapsed()) - entry.cycle().elapsed()));
    }
    private static void requireThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
    }
    private static Contact contact(ServerLevel level, BlockPos pos) {
        boolean unknown = false;
        for (Direction direction : Direction.values()) {
            var neighbor = pos.relative(direction);
            if (level.isOutsideBuildHeight(neighbor)) continue;
            if (!level.getChunkSource().hasChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4)) { unknown = true; continue; }
            var state = level.getBlockState(neighbor);
            var material = state.getBlock() instanceof CharredMaterialBlock block ? block : null;
            if (CharredMaterialRules.heatSource(state.is(Blocks.MAGMA_BLOCK), state.is(BlockTags.FIRE), state.getFluidState().is(FluidTags.LAVA),
                    material == null ? null : material.kind(), material != null && state.getValue(CharredMaterialBlock.LIT))) return Contact.PRESENT;
        }
        return unknown ? Contact.UNKNOWN : Contact.ABSENT;
    }
    /** Placement, tool state changes and six-face neighbor updates call this on the server. */
    public void synchronize(ServerLevel level, BlockPos pos) {
        requireThread(level);
        if (this != get(level)) throw new IllegalArgumentException("Wrong charred material dimension store");
        if (isReadOnly() || pos.equals(applying)) return;
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CharredMaterialBlock block)) { remove(pos); return; }
        var phase = state.getValue(CharredMaterialBlock.LIT) ? CharredMaterialRules.Phase.BURNING : CharredMaterialRules.Phase.IGNITION;
        var heat = phase == CharredMaterialRules.Phase.IGNITION ? contact(level, pos) : Contact.PRESENT;
        if (heat == Contact.ABSENT) { remove(pos); return; }
        var id = BuiltInRegistries.BLOCK.getKey(block);
        var old = entries.get(pos);
        if (heat == Contact.UNKNOWN) {
            if (old != null && old.block().equals(id) && old.cycle().kind() == block.kind()
                    && old.cycle().phase() != CharredMaterialRules.Phase.BURNING) return;
            phase = CharredMaterialRules.Phase.PROBING;
        }
        if (old != null && old.block().equals(id) && old.cycle().kind() == block.kind() && old.cycle().phase() == phase) return;
        remove(pos);
        var cycle = CharredMaterialRules.Cycle.start(block.kind(), phase, level.random);
        entries.put(pos.immutable(), new Entry(id, cycle));
        queue.updateSystem(task(pos), pos, cycle.target(), cycle.elapsed()); setDirty();
    }
    public void remove(BlockPos pos) {
        if (isReadOnly() || pos.equals(applying)) return;
        if (entries.remove(pos) != null) { queue.cancel(task(pos)); setDirty(); }
    }
    public void tick(ServerLevel level) {
        requireThread(level);
        if (isReadOnly() || applying != null) return;
        queue.tickSystems(level, this::setDirty, id -> {
            var pos = BlockPos.of(id.getLeastSignificantBits());
            var entry = entries.get(pos);
            if (entry == null) { queue.cancel(id); return; }
            var state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof CharredMaterialBlock block) || !BuiltInRegistries.BLOCK.getKey(block).equals(entry.block())
                    || block.kind() != entry.cycle().kind()) { remove(pos); return; }
            boolean ignition = entry.cycle().phase() == CharredMaterialRules.Phase.IGNITION;
            boolean probing = entry.cycle().phase() == CharredMaterialRules.Phase.PROBING;
            if (state.getValue(CharredMaterialBlock.LIT) != (entry.cycle().phase() == CharredMaterialRules.Phase.BURNING)) {
                remove(pos); synchronize(level, pos); return;
            }
            if (probing) {
                var heat = contact(level, pos);
                if (heat == Contact.UNKNOWN) return;
                remove(pos);
                if (heat == Contact.PRESENT) synchronize(level, pos);
                return;
            }
            if (ignition) {
                var heat = contact(level, pos);
                if (heat == Contact.ABSENT) { remove(pos); return; }
                if (heat == Contact.UNKNOWN) return; // Queue retries due work slowly without resetting elapsed progress.
            }
            boolean changed = false;
            try {
                var replacement = ignition ? state.setValue(CharredMaterialBlock.LIT, true) : block.burnedState(state);
                applying = pos;
                changed = level.setBlock(pos, replacement, Block.UPDATE_ALL);
            } catch (RuntimeException error) {
                var snapshot = cycle(pos).orElse(entry.cycle());
                entries.put(pos, new Entry(entry.block(), snapshot.fail())); queue.cancel(id); setDirty();
                org.slf4j.LoggerFactory.getLogger(CharredMaterialTimers.class).error("Charred material transition failed at " + pos, error);
            } finally { applying = null; }
            if (changed) { remove(pos); synchronize(level, pos); } // Actual final state wins after third-party neighbor callbacks.
        });
    }
    private static CharredMaterialTimers load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new CharredMaterialTimers();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.contains("Entries", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported charred material data");
            var list = tag.getList("Entries", Tag.TAG_COMPOUND);
            if (list.size() != ((ListTag) tag.get("Entries")).size()) throw new IllegalArgumentException("Invalid charred material list");
            for (var raw : list) {
                var saved = (CompoundTag) raw;
                for (String key : new String[]{"Block", "Kind", "Phase"})
                    if (!saved.contains(key, Tag.TAG_STRING)) throw new IllegalArgumentException("Missing " + key);
                if (!saved.contains("Position", Tag.TAG_LONG) || !saved.contains("Target", Tag.TAG_INT) || !saved.contains("Elapsed", Tag.TAG_INT)
                        || !saved.contains("Failed", Tag.TAG_BYTE) || saved.getByte("Failed") < 0 || saved.getByte("Failed") > 1)
                    throw new IllegalArgumentException("Incomplete charred material timer");
                var cycle = new CharredMaterialRules.Cycle(CharredMaterialRules.Kind.valueOf(saved.getString("Kind")),
                        CharredMaterialRules.Phase.valueOf(saved.getString("Phase")), saved.getInt("Target"), saved.getInt("Elapsed"), saved.getBoolean("Failed"));
                var pos = BlockPos.of(saved.getLong("Position"));
                if (data.entries.putIfAbsent(pos, new Entry(ResourceLocation.parse(saved.getString("Block")), cycle)) != null)
                    throw new IllegalArgumentException("Duplicate charred material timer");
            }
            data.entries.forEach((pos, entry) -> {
                var cycle = entry.cycle(); if (!cycle.failed()) data.queue.updateSystem(task(pos), pos, cycle.target(), cycle.elapsed());
            });
        } catch (IllegalArgumentException error) {
            data.entries.clear(); data.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(CharredMaterialTimers.class).error("Charred timers opened read-only; original payload preserved", error);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (isReadOnly()) return preserved.copy();
        tag.putInt("Version", 1); var list = new ListTag();
        entries.keySet().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).forEach(pos -> {
            var saved = new CompoundTag(); var entry = entries.get(pos); var cycle = cycle(pos).orElseThrow();
            saved.putLong("Position", pos.asLong()); saved.putString("Block", entry.block().toString());
            saved.putString("Kind", cycle.kind().name()); saved.putString("Phase", cycle.phase().name());
            saved.putInt("Target", cycle.target()); saved.putInt("Elapsed", cycle.elapsed()); saved.putBoolean("Failed", cycle.failed()); list.add(saved);
        });
        tag.put("Entries", list); return tag;
    }
}
