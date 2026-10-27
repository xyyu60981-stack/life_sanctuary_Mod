package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** 一袋最终产物的不可变输出快照；最多四个固定物品槽，抽取不会重新生成产物。 */
public final class CultivationOutputBatch {
    public static final int STACK_SLOTS = CultivationRules.OUTPUT_PER_BAG;
    private final CultivationBatch source;
    private final boolean failed;
    private final List<ItemStack> stacks;
    private final int remainingCount;

    /** 编解码恢复入口。调用者提供剩余实物，不按原始四份额度重新填满。 */
    CultivationOutputBatch(CultivationBatch source, List<ItemStack> remaining, boolean failed) {
        this.source = java.util.Objects.requireNonNull(source);
        this.failed = failed;
        if (!source.finalReady() || remaining.size() != STACK_SLOTS)
            throw new IllegalArgumentException("Final cultivation output requires four explicit stack slots");
        int total = 0;
        ItemStack first = ItemStack.EMPTY;
        var copy = new ArrayList<ItemStack>();
        for (var stack : remaining) {
            java.util.Objects.requireNonNull(stack);
            if (!stack.isEmpty()) {
                if (stack.getCount() > CultivationRules.OUTPUT_PER_BAG || stack.getCount() > stack.getMaxStackSize()
                        || !first.isEmpty() && !ItemStack.isSameItemSameComponents(first, stack))
                    throw new IllegalArgumentException("Invalid cultivation output stack");
                if (first.isEmpty()) first = stack;
                total += stack.getCount();
            }
            copy.add(stack.copy());
        }
        if (total < 1 || total > CultivationRules.OUTPUT_PER_BAG) throw new IllegalArgumentException("Invalid cultivation output remainder");
        stacks = List.copyOf(copy);
        remainingCount = total;
    }

    /** 只为最终成熟批次构建一次四份产物提案；调用者仍须与清除原批次共同提交。 */
    public static Optional<CultivationOutputBatch> prepare(CultivationBatch batch) {
        var product = batch.automatedProduct().orElse(null);
        if (product == null) return Optional.empty();
        var resolved = CultivationProducts.resolve(product).orElse(null);
        if (resolved == null || resolved.size() > STACK_SLOTS) return Optional.empty();
        var slots = new ArrayList<ItemStack>(Collections.nCopies(STACK_SLOTS, ItemStack.EMPTY));
        for (int i = 0; i < resolved.size(); i++) slots.set(i, resolved.get(i));
        return Optional.of(new CultivationOutputBatch(batch, slots, false));
    }

    public java.util.UUID id() { return source.id(); }
    public CultivationBatch source() { return source; }
    public CultivationBatch.Product product() { return source.automatedProduct().orElseThrow(); }
    public boolean failed() { return failed; }
    public CultivationOutputBatch freezeForRecovery() { return failed ? this : new CultivationOutputBatch(source, stacks, true); }
    public List<ItemStack> remainingStacks() { return stacks.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList(); }
    public int remainingCount() { return remainingCount; }
    public ItemStack stackAt(int slot) { return stacks.get(slot).copy(); }

    public record Extraction(ItemStack stack, Optional<CultivationOutputBatch> after) {
        public Extraction { stack = stack.copy(); java.util.Objects.requireNonNull(after); }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    /** 纯提案可供模拟；未写回 after 就没有消费。最后一件取完后 after 为空，槽号不前移。 */
    public Optional<Extraction> extract(int slot, int amount) {
        var current = stacks.get(slot);
        if (failed || amount <= 0 || current.isEmpty()) return Optional.empty();
        int taken = Math.min(amount, current.getCount());
        var result = current.copyWithCount(taken);
        if (taken == remainingCount) return Optional.of(new Extraction(result, Optional.empty()));
        var next = new ArrayList<>(stacks);
        next.set(slot, taken == current.getCount() ? ItemStack.EMPTY : current.copyWithCount(current.getCount() - taken));
        return Optional.of(new Extraction(result, Optional.of(new CultivationOutputBatch(source, next, false))));
    }
}
