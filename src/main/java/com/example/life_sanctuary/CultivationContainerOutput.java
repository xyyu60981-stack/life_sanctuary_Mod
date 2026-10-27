package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** 向明确授权的 Container 槽交付整批产物；调用方负责权限、侧面规则和服务器线程。 */
public final class CultivationContainerOutput implements CultivationInventoryExchange.Output {
    private final Container target;
    private final int containerSize;
    private final List<Integer> slots;
    private final List<ItemStack> expected;
    private boolean delivering;

    /** 槽顺序决定同类合并及空槽填充顺序；只保存这些槽的完整快照，不搜索其他容器。 */
    public CultivationContainerOutput(Container target, List<Integer> authorizedSlots) {
        this.target = java.util.Objects.requireNonNull(target);
        containerSize = target.getContainerSize();
        slots = List.copyOf(authorizedSlots);
        var unique = new HashSet<Integer>();
        var snapshot = new ArrayList<ItemStack>();
        for (int slot : slots) {
            if (slot < 0 || slot >= containerSize || !unique.add(slot))
                throw new IllegalArgumentException("Invalid or duplicate cultivation output slot");
            snapshot.add(target.getItem(slot).copy());
        }
        expected = List.copyOf(snapshot);
    }

    @Override public boolean deliver(CultivationBatch.Product product) {
        var resolved = CultivationProducts.resolve(product);
        return resolved.isPresent() && deliverStored(resolved.get());
    }

    @Override public boolean deliverStored(List<ItemStack> stacks) {
        if (delivering) throw new IllegalStateException("Reentrant cultivation output delivery");
        delivering = true;
        try {
            return deliverOnce(stacks.stream().map(ItemStack::copy).toList());
        } finally { delivering = false; }
    }

    private boolean deliverOnce(List<ItemStack> stacks) {
        if (!matches(expected)) return false;
        if (stacks.isEmpty() || stacks.stream().anyMatch(ItemStack::isEmpty)) return false;
        var proposed = new ArrayList<ItemStack>();
        for (var stack : expected) proposed.add(stack.copy());
        // 先合并同类再占空槽；所有容量计算只修改副本，装不下时真实库存完全不变。
        boolean fits = true;
        for (var incoming : stacks) {
            int remaining = incoming.getCount();
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 0; i < slots.size() && remaining > 0; i++) {
                    var current = proposed.get(i);
                    if (pass == 0 ? current.isEmpty() : !current.isEmpty()) continue;
                    if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, incoming)) continue;
                    if (!target.canPlaceItem(slots.get(i), incoming.copy())) continue;
                    int limit = Math.min(target.getMaxStackSize(), incoming.getMaxStackSize());
                    int amount = Math.min(remaining, Math.max(0, limit - current.getCount()));
                    if (amount == 0) continue;
                    proposed.set(i, incoming.copyWithCount(current.getCount() + amount));
                    remaining -= amount;
                }
            }
            if (remaining > 0) { fits = false; break; }
        }
        // canPlaceItem 是外部代码；若其修改库存，不能按普通容量不足返回 false。
        if (!matches(expected)) throw new IllegalStateException("Cultivation output changed during capacity checks");
        if (!fits) return false;
        var written = new ArrayList<ItemStack>(expected);
        for (int i = 0; i < slots.size(); i++) {
            if (same(expected.get(i), proposed.get(i))) continue;
            target.setItem(slots.get(i), proposed.get(i).copy());
            written.set(i, proposed.get(i));
            // 修改开始后任何意外结果都抛错，由控制器冻结原批次，避免重复出货。
            if (!matches(written)) throw new IllegalStateException("Cultivation output container rejected or altered delivery");
        }
        target.setChanged();
        if (!matches(proposed)) throw new IllegalStateException("Cultivation output changed while marking container dirty");
        return true;
    }

    private boolean matches(List<ItemStack> snapshot) {
        if (target.getContainerSize() != containerSize) return false;
        for (int i = 0; i < slots.size(); i++)
            if (!same(target.getItem(slots.get(i)), snapshot.get(i))) return false;
        return true;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first.isEmpty() && second.isEmpty()
                || first.getCount() == second.getCount() && ItemStack.isSameItemSameComponents(first, second);
    }
}
