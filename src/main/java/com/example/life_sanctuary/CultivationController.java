package com.example.life_sanctuary;

import java.util.UUID;
import net.minecraft.util.RandomSource;

/**
 * 服务器线程内的两槽／四槽事务控制器；由方块实体持有，并在 CHANGED/RECOVERY_REQUIRED 后标记保存。
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

    /**
     * 未来方块实体仅在服务器活动 tick 调用一次；各槽累计一 tick，不按世界时间补算。
     * 中间段成熟后最多前进一段，新段从零开始；不自动取出产物或投入新袋。
     */
    public Result tick(boolean powered, RandomSource random) {
        requireThread();
        java.util.Objects.requireNonNull(random);
        if (applying || !(data instanceof CultivationSlotsCodec.Loaded loaded) || loaded.state().isReadOnly())
            return Result.REJECTED;
        if (!powered) return Result.UNCHANGED;
        boolean changed = false;
        var current = loaded.state();
        for (int slot = 0; slot < current.capacity(); slot++) {
            var batch = current.batchAt(slot).orElse(null);
            if (batch == null || batch.progress().failed() || batch.finalReady()) continue;
            if (advance(current, slot, 1, true) == Result.CHANGED) {
                changed = true;
                current = ((CultivationSlotsCodec.Loaded) data).state();
            }
            batch = current.batchAt(slot).orElseThrow();
            // 转换本身不再累计时间，防止同一活动 tick 同时计入前后两段。
            if (batch.progress().ready() && !batch.finalReady()
                    && continueBatch(current, slot, random) == Result.CHANGED) {
                changed = true;
                current = ((CultivationSlotsCodec.Loaded) data).state();
            }
        }
        return changed ? Result.CHANGED : Result.UNCHANGED;
    }

    public Result start(CultivationSlots expected, int slot, UUID batchId, CultivationRecipe recipe,
            RandomSource random, CultivationInventoryExchange.Input input) {
        requireThread();
        java.util.Objects.requireNonNull(batchId);
        java.util.Objects.requireNonNull(recipe);
        java.util.Objects.requireNonNull(input);
        if (!matches(expected, slot) || !expected.canInsertAt(slot)
                || !CultivationRecipes.find(recipe.species()).filter(recipe::equals).isPresent()
                || !CultivationProducts.isReady(recipe)) return Result.REJECTED;
        for (int i = 0; i < expected.capacity(); i++)
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
        // 旧存档配方仍使用自身快照；当前产物处理器缺失时暂停，不重抽或冻结批次。
        if (!CultivationProducts.isAvailable(batch.recipe().species(), batch.phase().output())) return Result.UNCHANGED;
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
        var nextPhase = batch.recipe().phases().get(batch.phaseIndex() + 1);
        if (!CultivationProducts.isAvailable(batch.recipe().species(), nextPhase.output())) return Result.REJECTED;
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
        return !applying && data instanceof CultivationSlotsCodec.Loaded loaded && loaded.state().equals(expected)
                && slot >= 0 && slot < expected.capacity()
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
