package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** 每半柜两组输出占位；待取完前占用原培育名额，不与新袋混批。仅生成不可变提案。 */
public record CultivationOutputSlots(List<Optional<CultivationOutputBatchCodec.ReadResult>> slots) {
    public CultivationOutputSlots {
        slots = List.copyOf(slots);
        if (slots.size() != CultivationRules.SINGLE_SLOT_COUNT) throw new IllegalArgumentException("Two local output groups required");
        var ids = new HashSet<UUID>();
        for (var slot : slots)
            if (slot.orElse(null) instanceof CultivationOutputBatchCodec.Loaded loaded && !ids.add(loaded.batch().id()))
                throw new IllegalArgumentException("Duplicate cultivation output batch identity");
    }

    public static CultivationOutputSlots empty() {
        return new CultivationOutputSlots(Collections.nCopies(CultivationRules.SINGLE_SLOT_COUNT,
                Optional.<CultivationOutputBatchCodec.ReadResult>empty()));
    }

    public boolean isReadOnly() {
        return slots.stream().anyMatch(slot -> slot.orElse(null) instanceof CultivationOutputBatchCodec.Preserved);
    }

    /** 不可读组也占位；整机只读时未来调用者不能据另一组为空就接收新袋。 */
    public boolean occupies(int slot) { return slots.get(slot).isPresent(); }

    public Optional<CultivationOutputBatch> batchAt(int slot) {
        return slots.get(slot).filter(value -> value instanceof CultivationOutputBatchCodec.Loaded)
                .map(value -> ((CultivationOutputBatchCodec.Loaded) value).batch());
    }

    /** 与清除原培育批次共同提交；仅接收该批次的完整四份最终实物，禁止覆盖已有余量。 */
    public Optional<CultivationOutputSlots> deposit(int slot, CultivationBatch source, CultivationOutputBatch output) {
        java.util.Objects.requireNonNull(source);
        java.util.Objects.requireNonNull(output);
        if (isReadOnly() || occupies(slot) || !source.finalReady() || !source.id().equals(output.id())
                || !source.automatedProduct().filter(output.product()::equals).isPresent()
                || !source.equals(output.source()) || output.failed()
                || output.remainingCount() != CultivationRules.OUTPUT_PER_BAG) return Optional.empty();
        for (int i = 0; i < slots.size(); i++) if (batchAt(i).filter(batch -> batch.id().equals(output.id())).isPresent())
            return Optional.empty();
        return Optional.of(replace(slot, Optional.of(new CultivationOutputBatchCodec.Loaded(output))));
    }

    public record Extraction(CultivationOutputSlots after, ItemStack stack) {
        public Extraction { java.util.Objects.requireNonNull(after); stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public Optional<CultivationOutputSlots> remove(int slot, CultivationOutputBatch expected) {
        if (isReadOnly() || expected == null || expected.failed() || batchAt(slot).orElse(null) != expected) return Optional.empty();
        return Optional.of(replace(slot, Optional.empty()));
    }

    public CultivationOutputSlots freeze(int slot, CultivationOutputBatch expected) {
        if (batchAt(slot).orElse(null) != expected) throw new IllegalStateException("Stale output recovery proposal");
        return replace(slot, Optional.of(new CultivationOutputBatchCodec.Loaded(expected.freezeForRecovery())));
    }

    /** expected 必须为当前不可变对象，旧快照即使 UUID 相同也不能再次扣取；模拟不提交 after。 */
    public Optional<Extraction> extract(int slot, CultivationOutputBatch expected, int stackSlot, int amount) {
        if (isReadOnly() || batchAt(slot).orElse(null) != expected || expected == null) return Optional.empty();
        return expected.extract(stackSlot, amount).map(extraction -> new Extraction(replace(slot,
                extraction.after().map(value -> (CultivationOutputBatchCodec.ReadResult) new CultivationOutputBatchCodec.Loaded(value))),
                extraction.stack()));
    }

    private CultivationOutputSlots replace(int slot, Optional<CultivationOutputBatchCodec.ReadResult> value) {
        var next = new ArrayList<>(slots);
        next.set(slot, value);
        return new CultivationOutputSlots(next);
    }
}
