package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 两槽／四槽独立 V2 格式；V1 明确按旧四槽读取，不据记录缺失猜测空闲。 */
public final class CultivationSlotsCodec {
    private CultivationSlotsCodec() {}
    private static final int VERSION = 2;
    public sealed interface ReadResult permits Loaded, Preserved {}
    public record Loaded(CultivationSlots state) implements ReadResult {
        public Loaded { java.util.Objects.requireNonNull(state); }
    }
    public record Preserved(CompoundTag data, String reason) implements ReadResult {
        public Preserved {
            data = data.copy();
            java.util.Objects.requireNonNull(reason);
        }
        @Override public CompoundTag data() { return data.copy(); }
    }

    public static ReadResult read(CompoundTag saved) {
        java.util.Objects.requireNonNull(saved);
        try {
            require(saved, "Version", Tag.TAG_INT);
            int version = saved.getInt("Version");
            if (version != 1 && version != VERSION) throw new IllegalArgumentException("Unsupported cultivation slots version");
            int capacity = CultivationRules.LARGE_SLOT_COUNT;
            if (version == VERSION) {
                require(saved, "Capacity", Tag.TAG_INT);
                capacity = saved.getInt("Capacity");
                if (!CultivationRules.validSlotCount(capacity)) throw new IllegalArgumentException("Invalid cultivation capacity");
            }
            require(saved, "Slots", Tag.TAG_LIST);
            var entries = saved.getList("Slots", Tag.TAG_COMPOUND);
            if (entries.size() != capacity || entries.size() != ((ListTag) saved.get("Slots")).size())
                throw new IllegalArgumentException("Incomplete cultivation slots list");
            var slots = new ArrayList<Optional<CultivationBatchCodec.ReadResult>>(Collections.nCopies(
                    capacity, Optional.<CultivationBatchCodec.ReadResult>empty()));
            var seen = new boolean[capacity];
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                require(entry, "Slot", Tag.TAG_INT);
                require(entry, "Occupied", Tag.TAG_BYTE);
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= capacity || seen[slot])
                    throw new IllegalArgumentException("Duplicate or invalid cultivation slot index");
                seen[slot] = true;
                byte occupied = entry.getByte("Occupied");
                if (occupied != 0 && occupied != 1) throw new IllegalArgumentException("Invalid cultivation occupied flag");
                if (occupied == 1) {
                    require(entry, "Batch", Tag.TAG_COMPOUND);
                    slots.set(slot, Optional.of(CultivationBatchCodec.read(entry.getCompound("Batch"))));
                } else if (entry.contains("Batch")) throw new IllegalArgumentException("Empty cultivation slot contains batch data");
            }
            // 构造器再次校验批次 UUID 唯一性。不可读子批次保留占槽，并令整机只读。
            return new Loaded(new CultivationSlots(slots));
        } catch (RuntimeException failure) {
            return new Preserved(saved, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public static CompoundTag write(CultivationSlots state) {
        var saved = new CompoundTag();
        saved.putInt("Version", VERSION);
        saved.putInt("Capacity", state.capacity());
        var entries = new ListTag();
        for (int slot = 0; slot < state.capacity(); slot++) {
            var entry = new CompoundTag();
            entry.putInt("Slot", slot);
            var batch = state.slots().get(slot);
            entry.putBoolean("Occupied", batch.isPresent());
            batch.ifPresent(value -> entry.put("Batch", CultivationBatchCodec.write(value)));
            entries.add(entry);
        }
        saved.put("Slots", entries);
        return saved;
    }

    public static CompoundTag write(ReadResult result) {
        java.util.Objects.requireNonNull(result);
        return result instanceof Preserved preserved ? preserved.data() : write(((Loaded) result).state());
    }

    private static void require(CompoundTag saved, String key, int type) {
        if (!saved.contains(key, type)) throw new IllegalArgumentException("Missing or invalid cultivation slots field: " + key);
    }
}
