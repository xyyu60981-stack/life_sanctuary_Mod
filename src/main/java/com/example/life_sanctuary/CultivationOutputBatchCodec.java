package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** 最终输出批次独立 V2 格式；保存原批次、冻结标记及剩余实物，不按当前映射重新生成。 */
public final class CultivationOutputBatchCodec {
    private CultivationOutputBatchCodec() {}
    private static final int VERSION = 2;
    public sealed interface ReadResult permits Loaded, Preserved {}
    public record Loaded(CultivationOutputBatch batch) implements ReadResult {
        public Loaded { java.util.Objects.requireNonNull(batch); }
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
            if (saved.getInt("Version") != VERSION)
                throw new IllegalArgumentException("Unsupported or unidentified cultivation output batch");
            require(saved, "Source", Tag.TAG_COMPOUND);
            require(saved, "Failed", Tag.TAG_BYTE);
            byte failed = saved.getByte("Failed");
            if (failed != 0 && failed != 1) throw new IllegalArgumentException("Invalid output recovery flag");
            var source = CultivationBatchCodec.read(saved.getCompound("Source"));
            if (!(source instanceof CultivationBatchCodec.Loaded loadedSource))
                throw new IllegalArgumentException("Unreadable output source batch");
            require(saved, "Slots", Tag.TAG_LIST);
            var entries = saved.getList("Slots", Tag.TAG_COMPOUND);
            if (entries.size() != CultivationOutputBatch.STACK_SLOTS || entries.size() != ((ListTag) saved.get("Slots")).size())
                throw new IllegalArgumentException("Incomplete cultivation output stack list");
            var stacks = new ArrayList<ItemStack>(Collections.nCopies(CultivationOutputBatch.STACK_SLOTS, ItemStack.EMPTY));
            var seen = new boolean[CultivationOutputBatch.STACK_SLOTS];
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i);
                require(entry, "Slot", Tag.TAG_INT);
                require(entry, "Stack", Tag.TAG_COMPOUND);
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= seen.length || seen[slot]) throw new IllegalArgumentException("Invalid output slot index");
                seen[slot] = true;
                var stackTag = entry.getCompound("Stack");
                var stack = ItemStack.parseOptional(registries, stackTag);
                if (!stackTag.isEmpty() && stack.isEmpty()) throw new IllegalArgumentException("Unreadable output item stack");
                stacks.set(slot, stack);
            }
            return new Loaded(new CultivationOutputBatch(loadedSource.batch(), stacks, failed == 1));
        } catch (RuntimeException failure) {
            return new Preserved(saved, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    public static CompoundTag write(CultivationOutputBatch batch, HolderLookup.Provider registries) {
        var saved = new CompoundTag();
        saved.putInt("Version", VERSION);
        saved.put("Source", CultivationBatchCodec.write(batch.source()));
        saved.putBoolean("Failed", batch.failed());
        var entries = new ListTag();
        for (int i = 0; i < CultivationOutputBatch.STACK_SLOTS; i++) {
            var entry = new CompoundTag();
            entry.putInt("Slot", i);
            entry.put("Stack", batch.stackAt(i).saveOptional(registries));
            entries.add(entry);
        }
        saved.put("Slots", entries);
        return saved;
    }

    public static CompoundTag write(ReadResult result, HolderLookup.Provider registries) {
        java.util.Objects.requireNonNull(result);
        return result instanceof Preserved preserved ? preserved.data() : write(((Loaded) result).batch(), registries);
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or invalid cultivation output field: " + key);
    }
}
