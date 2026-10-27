package com.example.life_sanctuary;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 固定地图的河流四邻湿润索引；建图时扫描一次，不由服务器 tick 重建。
 * 一次水路径、流向、触发及消失由另一阶段处理，此类不作推断。
 */
public final class CooksoniaRiverMoisture {
    private final int size;
    private final Set<CooksoniaBoard.Pos> rivers;
    private final Set<CooksoniaBoard.Pos> neighbors;

    private CooksoniaRiverMoisture(int size, Set<CooksoniaBoard.Pos> rivers, Set<CooksoniaBoard.Pos> neighbors) {
        this.size = size;
        this.rivers = Set.copyOf(rivers);
        this.neighbors = Set.copyOf(neighbors);
    }

    public static CooksoniaRiverMoisture index(CooksoniaBoard board) {
        Objects.requireNonNull(board);
        var rivers = new HashSet<CooksoniaBoard.Pos>();
        var neighbors = new HashSet<CooksoniaBoard.Pos>();
        for (int y = 0; y < board.size(); y++) {
            for (int x = 0; x < board.size(); x++) {
                var pos = new CooksoniaBoard.Pos(x, y);
                if (board.at(pos).terrain() == CooksoniaBoard.Terrain.RIVER) {
                    rivers.add(pos);
                    neighbors.addAll(board.cardinalNeighbors(pos));
                }
            }
        }
        return new CooksoniaRiverMoisture(board.size(), rivers, neighbors);
    }

    public Set<CooksoniaBoard.Pos> rivers() { return rivers; }
    public Set<CooksoniaBoard.Pos> neighbors() { return neighbors; }

    /**
     * 使用同一地图的后续地面，保留隐藏／寒冷／泥炭，只把邻格干燥转普通并标湿润。
     * 河流拓扑应固定；若未来机制新增或移动河流，须显式重建索引，不能复用旧索引。
     */
    public CooksoniaBoard apply(CooksoniaBoard current) {
        Objects.requireNonNull(current);
        if (current.size() != size) throw new IllegalArgumentException("River moisture index belongs to another board size");
        for (var pos : rivers) {
            if (current.at(pos).terrain() != CooksoniaBoard.Terrain.RIVER)
                throw new IllegalArgumentException("River layout changed; rebuild moisture index");
        }
        return current.wet(neighbors);
    }
}
