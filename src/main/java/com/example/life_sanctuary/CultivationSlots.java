package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 单柜两槽／连接大柜四槽的不可变状态，仅生成更新／领取提案，不直接修改真实库存。
 * 方块实体须比较完整旧快照后连同输入消费或输出交付一次提交，不能只调用查询就给物品。
 */
public record CultivationSlots(List<Optional<CultivationBatchCodec.ReadResult>> slots) {
    public CultivationSlots {
        slots = List.copyOf(slots);
        if (!CultivationRules.validSlotCount(slots.size())) throw new IllegalArgumentException("Cultivation cabinet needs two or four slots");
        var identities = new HashSet<UUID>();
        for (var slot : slots) {
            if (slot.orElse(null) instanceof CultivationBatchCodec.Loaded loaded && !identities.add(loaded.batch().id()))
                throw new IllegalArgumentException("One cultivation batch cannot occupy multiple slots");
        }
    }

    public static CultivationSlots empty() {
        return empty(CultivationRules.SINGLE_SLOT_COUNT);
    }

    public static CultivationSlots empty(int capacity) {
        if (!CultivationRules.validSlotCount(capacity)) throw new IllegalArgumentException("Invalid cultivation capacity");
        return new CultivationSlots(Collections.nCopies(capacity,
                Optional.<CultivationBatchCodec.ReadResult>empty()));
    }

    public int capacity() { return slots.size(); }

    /** 任一不可读槽存在时整机禁止变更，避免将未知批次误判为空槽或与其他槽重复。 */
    public boolean isReadOnly() {
        return slots.stream().anyMatch(slot -> slot.orElse(null) instanceof CultivationBatchCodec.Preserved);
    }

    /** 空返回也可能是不可读槽；能否投入必须检查 canInsertAt，不能据此认定空闲。 */
    public Optional<CultivationBatch> batchAt(int slot) {
        return slots.get(slot).filter(value -> value instanceof CultivationBatchCodec.Loaded)
                .map(value -> ((CultivationBatchCodec.Loaded) value).batch());
    }

    public boolean canInsertAt(int slot) { return slots.get(slot).isEmpty() && !isReadOnly(); }

    /** 只接受全新的首段零进度批次；真正消耗一袋输入由后续库存事务负责。 */
    public Optional<CultivationSlots> insert(int slot, CultivationBatch batch) {
        java.util.Objects.requireNonNull(batch);
        if (!canInsertAt(slot) || batch.phaseIndex() != 0 || batch.progress().elapsedTicks() != 0 || batch.progress().failed()
                || slots.stream().anyMatch(value -> value.orElse(null) instanceof CultivationBatchCodec.Loaded loaded
                    && loaded.batch().id().equals(batch.id()))) return Optional.empty();
        return Optional.of(replace(slot, Optional.of(new CultivationBatchCodec.Loaded(batch))));
    }

    public Optional<CultivationSlots> updateProgress(int slot, CultivationBatch expected, CultivationBatch next) {
        if (isReadOnly() || !batchAt(slot).filter(expected::equals).isPresent() || !expected.permitsProgress(next))
            return Optional.empty();
        return Optional.of(replace(slot, Optional.of(new CultivationBatchCodec.Loaded(next))));
    }

    /** 成熟中间段仅能前进一段，新段从零开始；失败、最终完成及旧快照均拒绝。 */
    public Optional<CultivationSlots> continueBatch(int slot, CultivationBatch expected, CultivationBatch next) {
        java.util.Objects.requireNonNull(next);
        if (isReadOnly() || !batchAt(slot).filter(expected::equals).isPresent() || !expected.progress().ready()
                || expected.finalReady() || !expected.id().equals(next.id()) || !expected.recipe().equals(next.recipe())
                || next.phaseIndex() != expected.phaseIndex() + 1 || next.progress().elapsedTicks() != 0 || next.progress().failed())
            return Optional.empty();
        return Optional.of(replace(slot, Optional.of(new CultivationBatchCodec.Loaded(next))));
    }

    public record Extraction(CultivationSlots after, CultivationBatch.Product product) {}

    /** 必须以当前完整四槽快照为前提提交 after 和产物；模拟抽取不能写回 after。 */
    public Optional<Extraction> extract(int slot, CultivationBatch expected, boolean automated) {
        if (isReadOnly() || !batchAt(slot).filter(expected::equals).isPresent()) return Optional.empty();
        var product = automated ? expected.automatedProduct() : expected.manualProduct();
        return product.map(value -> new Extraction(replace(slot, Optional.empty()), value));
    }

    public int comparatorSignal() {
        if (isReadOnly()) return 0;
        int completed = 0;
        for (int slot = 0; slot < slots.size(); slot++)
            if (batchAt(slot).filter(CultivationBatch::finalReady).isPresent()) completed++;
        return CultivationRules.comparatorSignal(completed, capacity());
    }

    private CultivationSlots replace(int slot, Optional<CultivationBatchCodec.ReadResult> value) {
        var next = new ArrayList<>(slots);
        next.set(slot, value);
        return new CultivationSlots(next);
    }
}
