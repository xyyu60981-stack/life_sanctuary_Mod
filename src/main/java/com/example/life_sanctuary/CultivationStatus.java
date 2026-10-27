package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** 界面／Jade 用按需只读查询；不读世界、不生成物品、不推进计时或尝试库存写入。 */
public final class CultivationStatus {
    private CultivationStatus() {}
    public enum State {
        DATA_UNAVAILABLE, APPLYING, EMPTY, RECOVERY_REQUIRED, MISSING_OUTPUT,
        FINAL_READY, POWER_PAUSED, WAITING_NEXT_PHASE, GROWING
    }

    public record Batch(UUID id, ResourceLocation species, String phase, int phaseIndex, int phaseCount,
            long elapsedTicks, long targetTicks, boolean finalReady) {}
    public record BufferedProduct(ResourceLocation species, String phase, ResourceLocation output, int remaining, boolean failed) {}
    public record Slot(int index, State state, Optional<Batch> batch,
            Optional<CultivationBatch.Product> manualProduct,
            Optional<CultivationBatch.Product> automatedProduct, List<ResourceLocation> missingOutputs,
            Optional<BufferedProduct> bufferedProduct) {
        public Slot { missingOutputs = List.copyOf(missingOutputs); }
        public Slot(int index, State state, Optional<Batch> batch, Optional<CultivationBatch.Product> manualProduct,
                Optional<CultivationBatch.Product> automatedProduct, List<ResourceLocation> missingOutputs) {
            this(index, state, batch, manualProduct, automatedProduct, missingOutputs, Optional.empty());
        }
    }
    public record Snapshot(int capacity, boolean powered, boolean readOnly, boolean applying,
            int comparatorSignal, List<Slot> slots, Optional<String> diagnostic) {
        public Snapshot { slots = List.copyOf(slots); }
    }

    /** 在控制器所属服务器线程查询；事务期间不将旧快照显示为可再次领取。 */
    public static Snapshot inspect(CultivationController controller, boolean powered) {
        java.util.Objects.requireNonNull(controller);
        var data = controller.snapshot();
        boolean applying = controller.isApplying();
        return inspect(data, powered, applying);
    }

    static Snapshot inspect(CultivationSlotsCodec.ReadResult data, boolean powered, boolean applying) {
        if (data instanceof CultivationSlotsCodec.Preserved preserved)
            return new Snapshot(0, powered, true, applying, 0, List.of(), Optional.of(preserved.reason()));
        var slots = ((CultivationSlotsCodec.Loaded) data).state();
        boolean readOnly = slots.isReadOnly();
        var result = new ArrayList<Slot>();
        for (int i = 0; i < slots.capacity(); i++) {
            var batch = slots.batchAt(i).orElse(null);
            var details = batch == null ? Optional.<Batch>empty() : Optional.of(new Batch(batch.id(),
                    batch.recipe().species(), batch.phase().key(), batch.phaseIndex(), batch.recipe().phases().size(),
                    batch.progress().elapsedTicks(), batch.progress().targetTicks(), batch.finalReady()));
            var missing = batch == null ? List.<ResourceLocation>of() : CultivationProducts.missingOutputs(batch.recipe());
            State state;
            if (readOnly) state = State.DATA_UNAVAILABLE;
            else if (applying) state = State.APPLYING;
            else if (batch == null) state = State.EMPTY;
            else if (batch.progress().failed()) state = State.RECOVERY_REQUIRED;
            else if (!CultivationProducts.isAvailable(batch.recipe().species(), batch.phase().output())) state = State.MISSING_OUTPUT;
            else if (batch.finalReady()) state = State.FINAL_READY;
            else if (batch.progress().ready() && !CultivationProducts.isAvailable(batch.recipe().species(),
                    batch.recipe().phases().get(batch.phaseIndex() + 1).output())) state = State.MISSING_OUTPUT;
            else if (!powered) state = State.POWER_PAUSED;
            else if (batch.progress().ready()) state = State.WAITING_NEXT_PHASE;
            else state = State.GROWING;
            boolean accessible = !readOnly && !applying && batch != null;
            var manual = accessible ? batch.manualProduct().filter(CultivationStatus::hasMapping)
                    : Optional.<CultivationBatch.Product>empty();
            var automated = accessible ? batch.automatedProduct().filter(CultivationStatus::hasMapping)
                    : Optional.<CultivationBatch.Product>empty();
            result.add(new Slot(i, state, details, manual, automated, missing));
        }
        return new Snapshot(slots.capacity(), powered, readOnly, applying, slots.comparatorSignal(), result,
                readOnly ? Optional.of("Unreadable cultivation batch data") : Optional.empty());
    }

    private static boolean hasMapping(CultivationBatch.Product product) {
        return CultivationProducts.isAvailable(product.species(), product.output());
    }
}
