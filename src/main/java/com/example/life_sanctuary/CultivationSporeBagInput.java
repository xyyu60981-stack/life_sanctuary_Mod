package com.example.life_sanctuary;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** 从明确授权的原版 Container 槽消费一袋；不搜索背包或相邻容器，不绕过侧面访问规则。 */
public final class CultivationSporeBagInput implements CultivationInventoryExchange.Input {
    private final Container source;
    private final int slot;
    private final ItemStack expected;

    /** 由调用方先验证访问权限和允许输入的侧面，并提供当前槽完整快照。 */
    public CultivationSporeBagInput(Container source, int slot, ItemStack expected) {
        this.source = java.util.Objects.requireNonNull(source);
        if (slot < 0 || slot >= source.getContainerSize()) throw new IllegalArgumentException("Invalid cultivation input source slot");
        this.slot = slot;
        this.expected = expected.copy();
    }

    /** 支持本 mod 既有无命名空间标签及完整物种 ID；外部命名空间不缩写、不按路径混同。 */
    public static boolean matchesSpecies(ItemStack stack, ResourceLocation species) {
        if (!ReproductiveBagKind.SPORE.isFilledBag(stack) || !SporeBagContents.cultivatable(stack)) return false;
        String label = SporeBagItem.species(stack);
        return species.toString().equals(label)
                || species.getNamespace().equals("life_sanctuary") && species.getPath().equals(label);
    }

    @Override public boolean consumeOneSporeBag(ResourceLocation species) {
        var current = source.getItem(slot);
        if (!matchesSpecies(current, species) || current.getCount() != expected.getCount()
                || !ItemStack.isSameItemSameComponents(current, expected)) return false;
        // 此后任何异常都不能返回 false：容器可能已经扣过物品，交由控制器冻结批次。
        var removed = source.removeItem(slot, 1);
        if (removed.getCount() != 1 || !ItemStack.isSameItemSameComponents(removed, expected))
            throw new IllegalStateException("Cultivation source did not remove exactly the expected spore bag");
        var remaining = source.getItem(slot);
        int expectedCount = expected.getCount() - 1;
        if (expectedCount == 0 ? !remaining.isEmpty()
                : remaining.getCount() != expectedCount || !ItemStack.isSameItemSameComponents(remaining, expected))
            throw new IllegalStateException("Cultivation source changed unexpectedly during bag consumption");
        source.setChanged();
        return true;
    }
}
