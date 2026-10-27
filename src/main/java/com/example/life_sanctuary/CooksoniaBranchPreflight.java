package com.example.life_sanctuary;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 分叉只依赖明确的走茎前进方向；隐藏地格不因提示而泄露寒冷／干燥。 */
public final class CooksoniaBranchPreflight {
    private CooksoniaBranchPreflight() {}

    public enum Status {
        ALLOWED, UNKNOWN_STEM, MISSING_HEADING, RETIRED_PLANT, NO_BRANCHES,
        NOT_LATERAL, OUTSIDE_BOARD, OCCUPIED, FORBIDDEN_GROUND
    }

    public static Status inspect(CooksoniaGameState state, UUID stemId, CooksoniaHead.Heading direction) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(stemId);
        Objects.requireNonNull(direction);
        var pos = state.occupancy().positionOf(stemId).orElse(null);
        if (pos == null) return Status.UNKNOWN_STEM;
        var stem = state.occupancy().at(pos).orElseThrow();
        var geometry = geometry(state.board().size(), state.occupancy().entries(), state.plants(), pos, stem, direction);
        if (geometry != Status.ALLOWED) return geometry;
        return ground(state.board().at(direction.step(pos)).terrain());
    }

    public static Status inspect(CooksoniaView.Content visible, UUID stemId, CooksoniaHead.Heading direction) {
        Objects.requireNonNull(visible);
        Objects.requireNonNull(stemId);
        Objects.requireNonNull(direction);
        // 客户端仅在生成提示时查找单位；服务器使用已有单位索引。
        for (var entry : visible.occupants().entrySet()) {
            if (!entry.getValue().id().equals(stemId)) continue;
            var geometry = geometry(visible.size(), visible.occupants(), visible.plants(), entry.getKey(), entry.getValue(), direction);
            if (geometry != Status.ALLOWED) return geometry;
            var cell = visible.at(direction.step(entry.getKey()));
            return cell.ground().map(value -> ground(value.terrain())).orElse(Status.ALLOWED);
        }
        return Status.UNKNOWN_STEM;
    }

    private static Status geometry(int size, Map<CooksoniaBoard.Pos, CooksoniaOccupancy.Entry> occupants,
            Map<UUID, CooksoniaPlant> plants, CooksoniaBoard.Pos pos, CooksoniaOccupancy.Entry stem,
            CooksoniaHead.Heading direction) {
        if (stem.kind() != CooksoniaOccupancy.Kind.STEM) return Status.UNKNOWN_STEM;
        if (stem.stemHeading().isEmpty()) return Status.MISSING_HEADING;
        var plant = plants.get(stem.plantId());
        if (plant == null || plant.retired()) return Status.RETIRED_PLANT;
        if (plant.branches() == 0) return Status.NO_BRANCHES;
        var forward = stem.stemHeading().orElseThrow();
        if (direction != forward.left() && direction != forward.right()) return Status.NOT_LATERAL;
        var target = direction.step(pos);
        if (target.x() < 0 || target.y() < 0 || target.x() >= size || target.y() >= size) return Status.OUTSIDE_BOARD;
        if (occupants.containsKey(target)) return Status.OCCUPIED;
        return Status.ALLOWED;
    }

    private static Status ground(CooksoniaBoard.Terrain terrain) {
        return switch (terrain) {
            case COBBLESTONE, RIVER, ONE_SHOT_WATER -> Status.FORBIDDEN_GROUND;
            default -> Status.ALLOWED;
        };
    }
}
