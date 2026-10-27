package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

/** Only wet egg-bearing logs have entries. No block entities, timestamps or scans of loaded blocks. */
public final class HollowLogEggSoaking extends SavedData {
    private static final Factory<HollowLogEggSoaking> FACTORY = new Factory<>(HollowLogEggSoaking::new, HollowLogEggSoaking::load);
    private record Entry(ResourceLocation block, HollowLogEggRules.Timer timer, boolean failed) {}
    private final Map<BlockPos, Entry> entries = new HashMap<>();
    private final PlantGrowthScheduler queue = new PlantGrowthScheduler(32);
    private CompoundTag preserved;
    private BlockPos applying;

    public static HollowLogEggSoaking get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "life_sanctuary_hollow_log_eggs");
    }
    private static UUID task(BlockPos pos) { return new UUID(0, pos.asLong()); }
    public boolean isReadOnly() { return preserved != null; }
    public Optional<HollowLogEggRules.Timer> timer(BlockPos pos) {
        var entry = entries.get(pos);
        return entry == null ? Optional.empty() : Optional.of(new HollowLogEggRules.Timer(entry.timer().target(),
                (int) queue.currentElapsed(task(pos), entry.timer().elapsed())));
    }

    /** Called after placement or a real water/egg state transition; repeated updates do not reroll. */
    public void synchronize(ServerLevel level, BlockPos pos, RottenHollowLogBlock block) {
        requireThread(level);
        if (this != get(level)) throw new IllegalArgumentException("Wrong egg timer dimension store");
        if (isReadOnly() || pos.equals(applying)) return;
        var id = BuiltInRegistries.BLOCK.getKey(block);
        var old = entries.get(pos);
        if (!block.hasWetEggs(level.getBlockState(pos))) {
            if (old != null && old.block().equals(id)) remove(pos);
            return;
        }
        if (old != null && old.block().equals(id)) return;
        remove(pos);
        var timer = new HollowLogEggRules.Timer(HollowLogEggRules.soakingDuration(level.random), 0);
        entries.put(pos.immutable(), new Entry(id, timer, false));
        queue.updateSystem(task(pos), pos, timer.target(), timer.elapsed());
        setDirty();
    }
    public void remove(BlockPos pos) {
        if (isReadOnly() || pos.equals(applying)) return;
        if (entries.remove(pos) != null) { queue.cancel(task(pos)); setDirty(); }
    }
    private static void requireThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Server thread required");
    }
    public void tick(ServerLevel level) {
        requireThread(level);
        if (isReadOnly() || applying != null) return;
        queue.tickSystems(level, this::setDirty, id -> {
            var pos = BlockPos.of(id.getLeastSignificantBits());
            var entry = entries.get(pos);
            if (entry == null) { queue.cancel(id); return; }
            var state = level.getBlockState(pos); // Queue calls back only inside an actively ticking chunk.
            if (!(state.getBlock() instanceof RottenHollowLogBlock block)
                    || !BuiltInRegistries.BLOCK.getKey(block).equals(entry.block()) || !block.hasWetEggs(state)) {
                remove(pos); return;
            }
            try {
                applying = pos;
                // Only change eggs: retain axis, exact existing fluid state and other block properties.
                boolean changed = level.setBlock(pos, state.setValue(RottenHollowLogBlock.EGGS, false), Block.UPDATE_ALL);
                if (changed && !block.hasWetEggs(level.getBlockState(pos))) {
                    entries.remove(pos); queue.cancel(id); setDirty();
                }
            } catch (RuntimeException error) {
                entries.put(pos, new Entry(entry.block(), timer(pos).orElseThrow(), true));
                queue.cancel(id); setDirty();
                org.slf4j.LoggerFactory.getLogger(HollowLogEggSoaking.class).error("Egg soaking failed at " + pos, error);
            } finally { applying = null; }
            // A notification may replace the old log with another wet egg-bearing material.
            var after = level.getBlockState(pos);
            if (after.getBlock() instanceof RottenHollowLogBlock next && next.hasWetEggs(after)
                    && !BuiltInRegistries.BLOCK.getKey(next).equals(entry.block()))
                synchronize(level, pos, next);
        });
    }

    private static HollowLogEggSoaking load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new HollowLogEggSoaking();
        try {
            if (!tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.contains("Entries", Tag.TAG_LIST))
                throw new IllegalArgumentException("Unsupported egg timer data");
            var list = tag.getList("Entries", Tag.TAG_COMPOUND);
            if (list.size() != ((ListTag) tag.get("Entries")).size()) throw new IllegalArgumentException("Invalid egg timer list");
            for (var raw : list) {
                var saved = (CompoundTag) raw;
                if (!saved.contains("Position", Tag.TAG_LONG) || !saved.contains("Block", Tag.TAG_STRING)
                        || !saved.contains("Target", Tag.TAG_INT) || !saved.contains("Elapsed", Tag.TAG_INT)
                        || !saved.contains("Failed", Tag.TAG_BYTE) || saved.getByte("Failed") < 0 || saved.getByte("Failed") > 1)
                    throw new IllegalArgumentException("Incomplete egg timer");
                var pos = BlockPos.of(saved.getLong("Position"));
                var entry = new Entry(ResourceLocation.parse(saved.getString("Block")),
                        new HollowLogEggRules.Timer(saved.getInt("Target"), saved.getInt("Elapsed")), saved.getBoolean("Failed"));
                if (data.entries.putIfAbsent(pos, entry) != null) throw new IllegalArgumentException("Duplicate egg timer position");
            }
            data.entries.forEach((pos, entry) -> {
                if (!entry.failed()) data.queue.updateSystem(task(pos), pos, entry.timer().target(), entry.timer().elapsed());
            });
        } catch (IllegalArgumentException error) {
            data.entries.clear(); data.preserved = tag.copy();
            org.slf4j.LoggerFactory.getLogger(HollowLogEggSoaking.class).error("Egg timers opened read-only; original payload preserved", error);
        }
        return data;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (isReadOnly()) return preserved.copy();
        tag.putInt("Version", 1);
        var list = new ListTag();
        entries.keySet().stream().sorted(java.util.Comparator.comparingLong(BlockPos::asLong)).forEach(pos -> {
            var saved = new CompoundTag(); var entry = entries.get(pos); var timer = timer(pos).orElseThrow();
            saved.putLong("Position", pos.asLong()); saved.putString("Block", entry.block().toString());
            saved.putInt("Target", timer.target()); saved.putInt("Elapsed", timer.elapsed());
            saved.putBoolean("Failed", entry.failed()); list.add(saved);
        });
        tag.put("Entries", list); return tag;
    }
}
