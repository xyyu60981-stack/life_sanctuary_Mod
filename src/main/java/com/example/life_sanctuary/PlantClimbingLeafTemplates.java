package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** 依实际茎轴选择叶模板；X、Z横茎与Y直立茎都是合法着叶支撑，不从叶片高度猜测。 */
public record PlantClimbingLeafTemplates(Map<Direction.Axis, PlantLeafTemplate> templates) {
    public PlantClimbingLeafTemplates {
        templates = Map.copyOf(templates);
        if (!templates.keySet().equals(java.util.Set.of(Direction.Axis.values())))
            throw new IllegalArgumentException("Climbing leaves require templates for all three stem axes");
    }

    public PlantLeafTemplate.Prepared prepare(BlockPos stem, Direction.Axis axis, String role, boolean fertile, int collections) {
        return templates.get(Objects.requireNonNull(axis)).prepare(stem, role, fertile, collections);
    }
}
