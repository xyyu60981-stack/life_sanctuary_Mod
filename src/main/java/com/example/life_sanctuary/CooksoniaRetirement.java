package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 构建死亡快照前批量清理失活株走茎；零生命头保留原占位与阻挡。 */
public final class CooksoniaRetirement {
    private CooksoniaRetirement() {}

    public static CooksoniaOccupancy withoutRetiredStems(CooksoniaOccupancy occupancy, Map<UUID, CooksoniaPlant> plants) {
        Objects.requireNonNull(occupancy);
        Objects.requireNonNull(plants);
        var changes = new HashMap<CooksoniaBoard.Pos, CooksoniaOccupancy.Change>();
        for (var entry : occupancy.entries().entrySet()) {
            var unit = entry.getValue();
            if (unit.kind() != CooksoniaOccupancy.Kind.STEM) continue;
            var owner = plants.get(unit.plantId());
            if (owner == null) throw new IllegalArgumentException("Stem has no owning plant");
            if (owner.retired()) changes.put(entry.getKey(), new CooksoniaOccupancy.Change(unit, Optional.empty()));
        }
        return changes.isEmpty() ? occupancy : occupancy.changeAll(changes).orElseThrow();
    }
}
