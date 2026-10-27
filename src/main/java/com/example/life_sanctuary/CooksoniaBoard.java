package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 找泥炭小游戏的不可变二维地面；生成时提供全部实际地格，揭示不访问随机源。
 * 不包含头／走茎／配子体占位、资源扣除或通关结算，不读写 Minecraft 世界。
 */
public record CooksoniaBoard(int size, List<Cell> cells) {
    public enum Terrain { NORMAL, COLD, DRY, PEAT, COBBLESTONE, ONE_SHOT_WATER, RIVER }

    /** 棋盘坐标 x 向右、y 向下，与 Minecraft 世界三维坐标分开。 */
    public record Pos(int x, int y) {}

    /** 隐藏只是可见性，不能替代实际类型；湿润与地形分开，寒冷和泥炭不会被湿润清除。 */
    public record Cell(Terrain terrain, boolean revealed, boolean moist) {
        public Cell {
            java.util.Objects.requireNonNull(terrain);
            if (!revealed && (terrain == Terrain.COBBLESTONE || terrain == Terrain.ONE_SHOT_WATER || terrain == Terrain.RIVER))
                throw new IllegalArgumentException("Water and cobblestone cannot be hidden");
            if (moist && terrain == Terrain.DRY)
                throw new IllegalArgumentException("Moistened dry ground must become normal ground");
        }

        public Cell reveal() { return revealed ? this : new Cell(terrain, true, moist); }
        public Cell wet() {
            return moist ? this : new Cell(terrain == Terrain.DRY ? Terrain.NORMAL : terrain, revealed, true);
        }
    }

    public CooksoniaBoard {
        if (size <= 0) throw new IllegalArgumentException("Positive square board size required");
        cells = List.copyOf(cells);
        if (cells.size() != Math.multiplyExact(size, size)) throw new IllegalArgumentException("Incomplete square board");
    }

    public boolean contains(Pos pos) {
        java.util.Objects.requireNonNull(pos);
        return pos.x() >= 0 && pos.y() >= 0 && pos.x() < size && pos.y() < size;
    }

    private int index(Pos pos) {
        if (!contains(pos)) throw new IndexOutOfBoundsException("Position outside Cooksonia board");
        return pos.y() * size + pos.x();
    }

    public Cell at(Pos pos) { return cells.get(index(pos)); }

    /** 移动等规则的四邻查询，既不含斜角，也不因此揭示隐藏格。 */
    public List<Pos> cardinalNeighbors(Pos pos) {
        index(pos);
        var result = new ArrayList<Pos>(4);
        if (pos.y() > 0) result.add(new Pos(pos.x(), pos.y() - 1));
        if (pos.x() + 1 < size) result.add(new Pos(pos.x() + 1, pos.y()));
        if (pos.y() + 1 < size) result.add(new Pos(pos.x(), pos.y() + 1));
        if (pos.x() > 0) result.add(new Pos(pos.x() - 1, pos.y()));
        return List.copyOf(result);
    }

    /** 固定 3×3 揭示，包括中心；边界裁剪，不因不同揭示来源重抽地形。 */
    public CooksoniaBoard revealAround(Pos center) {
        return revealAround(center, false);
    }

    /** 移动落点的揭示和中心泥炭消耗合成一次地面复制；奖励仍由统一落点阶段处理。 */
    public CooksoniaBoard revealLanding(Pos center) {
        return revealAround(center, true);
    }

    private CooksoniaBoard revealAround(Pos center, boolean consumeCenterPeat) {
        index(center);
        var changes = new HashMap<Pos, Cell>();
        for (int y = Math.max(0, center.y() - 1); y <= Math.min(size - 1, center.y() + 1); y++)
            for (int x = Math.max(0, center.x() - 1); x <= Math.min(size - 1, center.x() + 1); x++) {
                var pos = new Pos(x, y);
                var cell = at(pos);
                var next = cell.reveal();
                if (consumeCenterPeat && pos.equals(center) && cell.terrain() == Terrain.PEAT)
                    next = new Cell(Terrain.NORMAL, true, cell.moist());
                if (!next.equals(cell)) changes.put(pos, next);
            }
        return changed(changes);
    }

    /** 调用方先决定水经过／四邻范围；这里只转换明确给出的格子，不推进回合或揭示。 */
    public CooksoniaBoard wet(Iterable<Pos> positions) {
        java.util.Objects.requireNonNull(positions);
        var changes = new HashMap<Pos, Cell>();
        for (var pos : positions) {
            var cell = at(pos);
            if (!cell.moist()) changes.put(pos, cell.wet());
        }
        return changed(changes);
    }

    /** 一次水已完成合法路径规划后，湿润明确范围并将原水格变普通；一次复制、不揭示。 */
    public CooksoniaBoard finishOneShotWater(Pos source, Iterable<Pos> wetPositions) {
        return finishOneShotWaters(Set.of(source), wetPositions);
    }

    /** 同次触发的多水源共用一次地面复制，流路必须先独立规划再合并湿润范围。 */
    public CooksoniaBoard finishOneShotWaters(Set<Pos> sources, Iterable<Pos> wetPositions) {
        var sourceCells = Set.copyOf(sources);
        if (sourceCells.isEmpty()) throw new IllegalArgumentException("No one-shot water sources");
        for (var source : sourceCells) {
            if (at(source).terrain() != Terrain.ONE_SHOT_WATER)
                throw new IllegalArgumentException("Missing one-shot water source");
        }
        var changes = new HashMap<Pos, Cell>();
        for (var pos : wetPositions) {
            var cell = at(pos);
            if (!cell.moist()) changes.put(pos, cell.wet());
        }
        for (var source : sourceCells) changes.put(source, new Cell(Terrain.NORMAL, at(source).revealed(), true));
        return changed(changes);
    }

    /** 仅地形变化提案；未来落点结算须同时处理 500 分和所属植株资源，不可单独发奖励。 */
    public CooksoniaBoard consumePeat(Pos pos) {
        var cell = at(pos);
        if (cell.terrain() != Terrain.PEAT) return this;
        return changed(Map.of(pos, new Cell(Terrain.NORMAL, cell.revealed(), cell.moist())));
    }

    /** 仅提出圆石变普通地面；扣镐和行动／生命由工具操作一起结算。 */
    public Optional<CooksoniaBoard> breakCobblestone(Pos pos) {
        var cell = at(pos);
        if (cell.terrain() != Terrain.COBBLESTONE) return Optional.empty();
        return Optional.of(changed(Map.of(pos, new Cell(Terrain.NORMAL, cell.revealed(), cell.moist()))));
    }

    private CooksoniaBoard changed(Map<Pos, Cell> changes) {
        if (changes.isEmpty()) return this;
        var next = new ArrayList<>(cells);
        changes.forEach((pos, cell) -> next.set(index(pos), cell));
        return new CooksoniaBoard(size, next);
    }
}
