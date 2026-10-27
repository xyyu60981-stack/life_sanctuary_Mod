package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 每半柜两组输出缓冲 V1 格式；不可读数据保留，不将失败读档视为取完或空名额。 */
public final class CultivationOutputSlotsCodec {
    private CultivationOutputSlotsCodec() {}
    private static final int VERSION = 1;
    public sealed interface ReadResult permits Loaded, Preserved {}
    public record Loaded(CultivationOutputSlots state) implements ReadResult {
        public Loaded { java.util.Objects.requireNonNull(state); }
    }
    public record Preserved(CompoundTag data, String reason) implements ReadResult {
        public Preserved { data = data.copy(); java.util.Objects.requireNonNull(reason); }
        @Override public CompoundTag data() { return data.copy(); }
    }

    public static ReadResult read(CompoundTag saved, HolderLookup.Provider registries) {
        java.util.Objects.requireNonNull(saved);
        java.util.Objects.requireNonNull(registries);
        try {
            require(saved, "Version", Tag.TAG_INT);
            if (saved.getInt("Version") != VERSION) throw new IllegalArgumentException("Unsupported cultivation output groups version");
            require(saved, "Groups", Tag.TAG_LIST);
            var entries = saved.getList("Groups", Tag.TAG_COMPOUND);
            int count = CultivationRules.SINGLE_SLOT_COUNT;
            if (entries.size() != count || entries.size() != ((ListTag) saved.get("Groups")).size())
                throw new IllegalArgumentException("Incomplete output group list");
            var groups = new ArrayList<Optional<CultivationOutputBatchCodec.ReadResult>>(Collections.nCopies(count,
                    Optional.<CultivationOutputBatchCodec.ReadResult>empty()));
            var seen = new boolean[count];
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                require(entry, "Slot", Tag.TAG_INT);
                require(entry, "Occupied", Tag.TAG_BYTE);
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= count || seen[slot]) throw new IllegalArgumentException("Invalid output group index");
                seen[slot] = true;
                byte occupied = entry.getByte("Occupied");
                if (occupied != 0 && occupied != 1) throw new IllegalArgumentException("Invalid output group occupied flag");
                if (occupied == 1) {
                    require(entry, "Batch", Tag.TAG_COMPOUND);
                    groups.set(slot, Optional.of(CultivationOutputBatchCodec.read(entry.getCompound("Batch"), registries)));
                } else if (entry.contains("Batch")) throw new IllegalArgumentException("Empty output group carries batch data");
            }
            return new Loaded(new CultivationOutputSlots(groups));
        } catch (RuntimeException failure) {
            return new Preserved(saved, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public static CompoundTag write(CultivationOutputSlots state, HolderLookup.Provider registries) {
        var saved = new CompoundTag();
        saved.putInt("Version", VERSION);
        var entries = new ListTag();
        for (int i = 0; i < CultivationRules.SINGLE_SLOT_COUNT; i++) {
            var entry = new CompoundTag();
            entry.putInt("Slot", i);
            var batch = state.slots().get(i);
            entry.putBoolean("Occupied", batch.isPresent());
            batch.ifPresent(value -> entry.put("Batch", CultivationOutputBatchCodec.write(value, registries)));
            entries.add(entry);
        }
        saved.put("Groups", entries);
        return saved;
    }

    public static CompoundTag write(ReadResult result, HolderLookup.Provider registries) {
        java.util.Objects.requireNonNull(result);
        return result instanceof Preserved preserved ? preserved.data() : write(((Loaded) result).state(), registries);
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or invalid output group field: " + key);
    }
}
