package com.example.life_sanctuary;

import java.util.Optional;
import net.minecraft.core.BlockPos;

/** 把实际命中的叶部件归一到独立叶身份，供破坏、采集和只读展示共用。 */
public final class PlantLeafTarget {
    private PlantLeafTarget() {}
    public record Target(PlantIndividualState individual, String key, PlantLeafState leaf) {}

    /**
     * 服务器线程调用，不读方块、不加载区块、不扫描历史叶。
     * 中心芽丛、主干及归属不明确的部件不视作叶；玩家距离／准星和操作权限由方块适配器验证。
     */
    public static Optional<Target> at(PlantIndividuals store, BlockPos hitPart) {
        if (store.isReadOnly() || store.isApplyingStructure()) return Optional.empty();
        var plant = store.atPart(hitPart).orElse(null);
        if (plant == null) return Optional.empty();
        var key = store.leafKeyAtPart(plant.id(), hitPart).orElse(null);
        if (key == null) return Optional.empty();
        var data = store.leavesOf(plant.id()).orElse(null);
        var leaf = data == null ? null : data.leaves().leaves().get(key);
        if (leaf == null || !leaf.present()
                || !store.partRole(plant.id(), leaf.anchor()).filter(leaf.role()::equals).isPresent()) return Optional.empty();
        return Optional.of(new Target(plant, key, leaf));
    }
}
