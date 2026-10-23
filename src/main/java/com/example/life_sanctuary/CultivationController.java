package com.example.life_sanctuary;

import java.util.UUID;
import net.minecraft.util.RandomSource;

/**
 * 服务器线程内的四槽事务控制器；由方块实体持有，并在 CHANGED/RECOVERY_REQUIRED 后标记保存。
 * 回调期间不暴露半提交状态，拦截重入；不保证第三方库存与区块存档跨文件的崩溃原子性。
 */
public final class CultivationController {
    public enum Result { CHANGED, UNCHANGED, REJECTED, RECOVERY_REQUIRED }
    private final Thread ownerThread = Thread.currentThread();
    private CultivationSlotsCodec.ReadResult data;
    private boolean applying;

    public CultivationController(CultivationSlotsCodec.ReadResult initial) { data = java.util.Objects.requireNonNull(initial); }
    public CultivationSlotsCodec.ReadResult snapshot() { requireThread(); return data; }
    public boolean isApplying() { requireThread(); return applying; }

    public Result start(CultivationSlots expected, int slot, UUID batchId, CultivationRecipe recipe,
            RandomSource random, CultivationInventoryExchange.Input input) {
        requireThread();
        java.util.Objects.requireNonNull(batchId);
        java.util.Objects.requireNonNull(recipe);
        java.util.Objects.requireNonNull(input);
        if (!matches(expected, slot) || !expected.canInsertAt(slot)
                || !CultivationRecipes.find(recipe.species()).filter(recipe::equals).isPresent()) return Result.REJECTED;
        for (int i = 0; i < CultivationRules.SLOT_COUNT; i++)
            if (expected.batchAt(i).filter(batch -> batch.id().equals(batchId)).isPresent()) return Result.REJECTED;
        applying = true;
        try {
            // 输入适配器尚未调用时，创建提案失败不会动真实库存。
            var batch = CultivationBatch.start(batchId, recipe, random);
            var proposed = expected.insert(slot, batch).orElseThrow();
            try {
                if (!input.consumeOneSporeBag(recipe.species())) return Result.REJECTED;
            } catch (RuntimeException failure) {
                freeze(proposed, slot, batch, failure);
                return Result.RECOVERY_REQUIRED;
            }
            data = new CultivationSlotsCodec.Loaded(proposed);
            return Result.CHANGED;
        } finally { applying = false; }
    }

    public Result advance(CultivationSlots expected, int slot, long activeTicks, boolean powered) {
        requireThread();
        if (!matches(expected, slot)) return Result.REJECTED;
        var batch = expected.batchAt(slot).orElse(null);
        if (batch == null) return Result.REJECTED;
        var next = batch.advance(activeTicks, powered);
        if (next == batch) return Result.UNCHANGED;
        data = new CultivationSlotsCodec.Loaded(expected.updateProgress(slot, batch, next).orElseThrow());
        return Result.CHANGED;
    }

    public Result continueBatch(CultivationSlots expected, int slot, RandomSource random) {
        requireThread();
        if (!matches(expected, slot)) return Result.REJECTED;
        var batch = expected.batchAt(slot).orElse(null);
        if (batch == null || !batch.progress().ready() || batch.finalReady()) return Result.REJECTED;
        applying = true;
        try {
            var next = batch.nextPhase(random).orElseThrow();
            data = new CultivationSlotsCodec.Loaded(expected.continueBatch(slot, batch, next).orElseThrow());
            return Result.CHANGED;
        } finally { applying = false; }
    }

    public Result extract(CultivationSlots expected, int slot, boolean automated, CultivationInventoryExchange.Output output) {
        requireThread();
        java.util.Objects.requireNonNull(output);
        if (!matches(expected, slot)) return Result.REJECTED;
        var batch = expected.batchAt(slot).orElse(null);
        if (batch == null) return Result.REJECTED;
        var proposal = expected.extract(slot, batch, automated).orElse(null);
        if (proposal == null) return Result.REJECTED;
        applying = true;
        try {
            try {
                if (!output.deliver(proposal.product())) return Result.REJECTED;
            } catch (RuntimeException failure) {
                freeze(expected, slot, batch, failure);
                return Result.RECOVERY_REQUIRED;
            }
            data = new CultivationSlotsCodec.Loaded(proposal.after());
            return Result.CHANGED;
        } finally { applying = false; }
    }

    private boolean matches(CultivationSlots expected, int slot) {
        return !applying && slot >= 0 && slot < CultivationRules.SLOT_COUNT
                && data instanceof CultivationSlotsCodec.Loaded loaded && loaded.state().equals(expected)
                && !expected.isReadOnly();
    }

    private void freeze(CultivationSlots state, int slot, CultivationBatch batch, RuntimeException failure) {
        data = new CultivationSlotsCodec.Loaded(state.updateProgress(slot, batch, batch.freezeForRecovery()).orElseThrow());
        org.slf4j.LoggerFactory.getLogger(CultivationController.class).error("Cultivation inventory outcome uncertain for " + batch.id(), failure);
    }

    private void requireThread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Cultivation controller must stay on its owning server thread");
    }
}
