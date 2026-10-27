package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 工具逐关开放规则；未配置的工具保持锁定，不猜测在哪一关开放。 */
public record CooksoniaToolRules(Map<Tool, Requirement> requirements) {
    public enum Tool { PICKAXE, SPORE, WAIT }
    /** 明确的空集合表示开局开放；非空集合表示这些关卡都已通关后开放。 */
    public record Requirement(Set<String> clearedLevels) {
        public Requirement {
            clearedLevels = Set.copyOf(clearedLevels);
            clearedLevels.forEach(CooksoniaProgress::requireId);
        }
    }

    public CooksoniaToolRules { requirements = Map.copyOf(requirements); }

    public static CooksoniaToolRules none() { return new CooksoniaToolRules(Map.of()); }

    public boolean allows(Tool tool, CooksoniaProgress progress) {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(progress);
        var requirement = requirements.get(tool);
        return requirement != null && requirement.clearedLevels().stream().allMatch(id -> progress.at(id).cleared());
    }

    public Set<Tool> unlocked(CooksoniaProgress progress) {
        var tools = new HashSet<Tool>();
        for (var tool : Tool.values()) if (allows(tool, progress)) tools.add(tool);
        return Set.copyOf(tools);
    }

    void validate(CooksoniaLevels levels) {
        for (var requirement : requirements.values()) {
            for (var id : requirement.clearedLevels()) {
                if (levels.find(id).isEmpty()) throw new IllegalArgumentException("Tool unlock references unknown Cooksonia level: " + id);
            }
        }
    }
}
