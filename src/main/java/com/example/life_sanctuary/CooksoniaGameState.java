package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 整局不可变权威状态提案：地面、单位、占位与资源一起校验，不支持逐层原地写入。
 * 由未来服务器会话持有；不保存棋盘到世界，也不信任客户端提供的分数／资源。
 */
public record CooksoniaGameState(CooksoniaBoard board, CooksoniaOccupancy occupancy,
        Map<UUID, CooksoniaPlant> plants, Map<UUID, CooksoniaHead> heads,
        Map<UUID, CooksoniaGametophyte> gametophytes,
        int spores, int waits, int pickaxes, long score, long turn, boolean cleared, int producedSpores) {
    /** 初始孢子库存不计作本局走茎产量；既有初始化入口从零累计。 */
    public CooksoniaGameState(CooksoniaBoard board, CooksoniaOccupancy occupancy,
            Map<UUID, CooksoniaPlant> plants, Map<UUID, CooksoniaHead> heads,
            Map<UUID, CooksoniaGametophyte> gametophytes,
            int spores, int waits, int pickaxes, long score, long turn, boolean cleared) {
        this(board, occupancy, plants, heads, gametophytes, spores, waits, pickaxes, score, turn, cleared, 0);
    }
    public CooksoniaGameState {
        java.util.Objects.requireNonNull(board);
        java.util.Objects.requireNonNull(occupancy);
        plants = Map.copyOf(plants);
        heads = Map.copyOf(heads);
        gametophytes = Map.copyOf(gametophytes);
        if (board.size() != occupancy.size() || spores < 0 || waits < 0 || pickaxes < 0 || score < 0 || turn < 0 || producedSpores < 0)
            throw new IllegalArgumentException("Invalid game board or global resources");
        var liveHeads = new HashMap<UUID, Integer>();
        for (var entry : plants.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().id())) throw new IllegalArgumentException("Mismatched plant key");
            liveHeads.put(entry.getKey(), 0);
        }
        for (var entry : heads.entrySet()) {
            var head = entry.getValue();
            if (!entry.getKey().equals(head.id()) || !plants.containsKey(head.plantId()) || !board.contains(head.pos()))
                throw new IllegalArgumentException("Unregistered or misplaced head");
            var cell = occupancy.at(head.pos()).orElse(null);
            if (cell == null || cell.kind() != CooksoniaOccupancy.Kind.HEAD
                    || !cell.id().equals(head.id()) || !cell.plantId().equals(head.plantId()))
                throw new IllegalArgumentException("Head and occupancy disagree");
            if (head.alive()) liveHeads.merge(head.plantId(), 1, Math::addExact);
        }
        for (var plant : plants.values()) {
            boolean hasLiveHead = liveHeads.get(plant.id()) > 0;
            if (plant.retired() == hasLiveHead)
                throw new IllegalArgumentException("Plant retirement and living heads disagree");
        }
        var plantIds = new HashSet<>(plants.keySet());
        for (var entry : gametophytes.entrySet()) {
            var gametophyte = entry.getValue();
            if (!entry.getKey().equals(gametophyte.id()) || !board.contains(gametophyte.pos())
                    || gametophyte.bornTurn() > turn || !plantIds.add(gametophyte.futurePlantId()))
                throw new IllegalArgumentException("Invalid gametophyte or reused future plant identity");
            var cell = occupancy.at(gametophyte.pos()).orElse(null);
            if (cell == null || cell.kind() != CooksoniaOccupancy.Kind.GAMETOPHYTE
                    || !cell.id().equals(gametophyte.id()) || !cell.plantId().equals(gametophyte.futurePlantId()))
                throw new IllegalArgumentException("Gametophyte and occupancy disagree");
        }
        // 反向校验，不能只验证单位有占位而遗漏幽灵头、幽灵配子体或无主走茎。
        for (var entry : occupancy.entries().entrySet()) {
            var cell = entry.getValue();
            switch (cell.kind()) {
                case HEAD -> {
                    var head = heads.get(cell.id());
                    if (head == null || !head.pos().equals(entry.getKey()) || !head.plantId().equals(cell.plantId()))
                        throw new IllegalArgumentException("Orphan head occupancy");
                }
                case STEM -> {
                    var owner = plants.get(cell.plantId());
                    if (owner == null || owner.retired()) throw new IllegalArgumentException("Orphan or retired stem occupancy");
                }
                case GAMETOPHYTE -> {
                    var gametophyte = gametophytes.get(cell.id());
                    if (gametophyte == null || !gametophyte.pos().equals(entry.getKey())
                            || !gametophyte.futurePlantId().equals(cell.plantId()))
                        throw new IllegalArgumentException("Orphan gametophyte occupancy");
                }
            }
        }
    }

    public Optional<CooksoniaHead> headAt(CooksoniaBoard.Pos pos) {
        return occupancy.at(pos).filter(entry -> entry.kind() == CooksoniaOccupancy.Kind.HEAD)
                .map(entry -> heads.get(entry.id()));
    }

    public Optional<CooksoniaPlant> plantOf(UUID headId) {
        var head = heads.get(headId);
        return head == null ? Optional.empty() : Optional.of(plants.get(head.plantId()));
    }
}
