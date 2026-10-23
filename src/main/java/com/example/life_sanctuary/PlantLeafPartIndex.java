package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 只索引仍有外形的叶角色；由资源变更／读档重建，不持久化、不逐 tick 重建。 */
final class PlantLeafPartIndex {
    private final Map<String, Set<String>> keysByRole;

    PlantLeafPartIndex(PlantLeafSet leaves) {
        Map<String, Set<String>> prepared = new HashMap<>();
        leaves.leaves().forEach((key, leaf) -> {
            if (leaf.present()) prepared.computeIfAbsent(leaf.role(), ignored -> new HashSet<>()).add(key);
        });
        Map<String, Set<String>> copied = new HashMap<>();
        prepared.forEach((role, keys) -> copied.put(role, Set.copyOf(keys)));
        keysByRole = Map.copyOf(copied);
    }

    Optional<String> keyForRole(String role) {
        String found = null;
        String current = role;
        while (!current.isEmpty()) {
            var matches = keysByRole.getOrDefault(current, Set.of());
            // 若适配器错误地将同一部件分给两片叶，拒绝而非按遍历顺序猜测。
            if (matches.size() > 1) return Optional.empty();
            if (!matches.isEmpty()) {
                String key = matches.iterator().next();
                if (found != null && !found.equals(key)) return Optional.empty();
                found = key;
            }
            int slash = current.lastIndexOf('/');
            if (slash < 0) break;
            current = current.substring(0, slash);
        }
        return Optional.ofNullable(found);
    }
}
