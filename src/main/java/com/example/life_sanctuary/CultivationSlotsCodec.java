package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 四槽容器独立 V1 格式；与方块实体其他字段分开保存，不据槽缺失猜测空闲。 */
public final class CultivationSlotsCodec {
    private CultivationSlotsCodec() {}
    private static final int VERSION = 1;
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
            if (saved.getInt("Version") != VERSION) throw new IllegalArgumentException("Unsupported cultivation slots version");
            require(saved, "Slots", Tag.TAG_LIST);
            var entries = saved.getList("Slots", Tag.TAG_COMPOUND);
            if (entries.size() != CultivationRules.SLOT_COUNT || entries.size() != ((ListTag) saved.get("Slots")).size())
                throw new IllegalArgumentException("Incomplete cultivation slots list");
            var slots = new ArrayList<Optional<CultivationBatchCodec.ReadResult>>(Collections.nCopies(
                    CultivationRules.SLOT_COUNT, Optional.<CultivationBatchCodec.ReadResult>empty()));
            var seen = new boolean[CultivationRules.SLOT_COUNT];
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                require(entry, "Slot", Tag.TAG_INT);
                require(entry, "Occupied", Tag.TAG_BYTE);
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= CultivationRules.SLOT_COUNT || seen[slot])
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
        var entries = new ListTag();
        for (int slot = 0; slot < CultivationRules.SLOT_COUNT; slot++) {
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
