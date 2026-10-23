package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 配置期注册的阶段叶形；保存不可变模板，不保存世界坐标、个体计时或采集额度。 */
public final class PlantLeafTemplates {
    private PlantLeafTemplates() {}
    private record Key(ResourceLocation species, String stage) {}

    /** 方向键就是稳定角色根，例如 leaf/slot_0；不是每轮变化的叶资源键。 */
    public record Profile(Map<String, PlantLeafTemplate> directions) {
        public Profile {
            directions = Map.copyOf(directions);
            if (directions.isEmpty()) throw new IllegalArgumentException("Empty leaf template profile");
            var occupied = new HashSet<BlockPos>();
            for (var entry : directions.entrySet()) {
                var role = entry.getKey();
                if (role.isBlank() || role.startsWith("/") || role.endsWith("/") || role.contains("//"))
                    throw new IllegalArgumentException("Invalid template direction role");
                int slash = role.lastIndexOf('/');
                while (slash >= 0) {
                    if (directions.containsKey(role.substring(0, slash)))
                        throw new IllegalArgumentException("Overlapping template direction roles");
                    slash = role.lastIndexOf('/', slash - 1);
                }
                for (var offset : entry.getValue().parts().keySet())
                    if (!occupied.add(offset)) throw new IllegalArgumentException("Leaf direction templates overlap in block space");
            }
        }

        /**
         * 生成全部方向的提案；不裁剪阻挡、不随机、不分配额度。
         * allocations 必须与全部方向一一对应，防止适配器不慎漏方向。
         */
        public Map<String, PlantLeafTemplate.Prepared> prepare(BlockPos center, Map<String, Allocation> allocations) {
            if (!allocations.keySet().equals(directions.keySet()))
                throw new IllegalArgumentException("Leaf allocations must cover every configured direction");
            var prepared = new HashMap<String, PlantLeafTemplate.Prepared>();
            for (var role : directions.keySet().stream().sorted().toList()) {
                var allocation = allocations.get(role);
                var leaf = directions.get(role).prepare(center, role, allocation.fertile(), allocation.remainingCollections());
                if (prepared.putIfAbsent(allocation.resourceKey(), leaf) != null)
                    throw new IllegalArgumentException("Duplicate leaf allocation resource key");
            }
            return Map.copyOf(prepared);
        }
    }

    /** 次数由阶段适配器明确给出；此记录不验证成年或代替泥炭等额度规则。 */
    public record Allocation(String resourceKey, boolean fertile, int remainingCollections) {
        public Allocation {
            if (resourceKey == null || resourceKey.isBlank() || remainingCollections < 0
                    || !fertile && remainingCollections != 0)
                throw new IllegalArgumentException("Invalid template leaf allocation");
        }
    }

    private static final Map<Key, Profile> PROFILES = new ConcurrentHashMap<>();

    /** 先注册物种阶段，再于配置期注册模板；重复条目拒绝，不能在 tick 中替换模板。 */
    public static void register(ResourceLocation species, String stage, Profile profile) {
        java.util.Objects.requireNonNull(profile);
        var definition = PlantSpeciesRegistry.find(species).orElse(null);
        if (definition == null || !definition.stages().containsKey(stage))
            throw new IllegalArgumentException("Leaf templates require a known species and stage");
        if (PROFILES.putIfAbsent(new Key(species, stage), profile) != null)
            throw new IllegalArgumentException("Duplicate stage leaf templates: " + species + "/" + stage);
    }

    /** 缺失时禁用该模板路径，不使用成年模板代替亚成体，也不据此创建任何任务。 */
    public static Optional<Profile> find(ResourceLocation species, String stage) {
        return Optional.ofNullable(PROFILES.get(new Key(species, stage)));
    }
}
