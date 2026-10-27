package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 面向未来 UI／网络的可见信息，绝不包含权威 GameState、Board 或移动伤害预检。 */
public record CooksoniaView(UUID sessionId, long revision, Content content, CooksoniaOutcome.Status outcome,
        int cooldownRemaining, Optional<CooksoniaWaterPlayback> waterPlayback, java.util.Set<CooksoniaToolRules.Tool> enabledTools) {
    public CooksoniaView(UUID sessionId, long revision, Content content, CooksoniaOutcome.Status outcome,
            int cooldownRemaining, Optional<CooksoniaWaterPlayback> waterPlayback) {
        this(sessionId, revision, content, outcome, cooldownRemaining, waterPlayback,
                java.util.Set.of(CooksoniaToolRules.Tool.values()));
    }
    public CooksoniaView {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(content);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(waterPlayback);
        enabledTools = java.util.Set.copyOf(enabledTools);
        if (revision < 0 || cooldownRemaining < 0 || cooldownRemaining > CooksoniaSession.ACTION_LOCK_TICKS
                || content.cleared() != outcome.cleared())
            throw new IllegalArgumentException("Invalid Cooksonia view status");
    }

    public record KnownGround(CooksoniaBoard.Terrain terrain, boolean moist) {
        public KnownGround {
            Objects.requireNonNull(terrain);
            if (moist && terrain == CooksoniaBoard.Terrain.DRY)
                throw new IllegalArgumentException("Visible moist ground cannot remain dry");
        }
    }

    /** 隐藏格只有 empty，不使用假普通地形或假湿润值混淆已知／未知。 */
    public record Cell(Optional<KnownGround> ground) {
        public Cell { Objects.requireNonNull(ground); }
        public boolean hidden() { return ground.isEmpty(); }
    }

    /** 与冷却外壳分离；整个不可变内容可在多次显示／冷却刷新间复用。 */
    public record Content(int size, List<Cell> cells,
            Map<CooksoniaBoard.Pos, CooksoniaOccupancy.Entry> occupants,
            Map<UUID, CooksoniaPlant> plants, Map<UUID, CooksoniaHead> heads,
            Map<UUID, CooksoniaGametophyte> gametophytes,
            int spores, int waits, int pickaxes, long score, long turn, boolean cleared, int producedSpores) {
        public Content {
            cells = List.copyOf(cells);
            occupants = Map.copyOf(occupants);
            plants = Map.copyOf(plants);
            heads = Map.copyOf(heads);
            gametophytes = Map.copyOf(gametophytes);
            if (size <= 0 || cells.size() != Math.multiplyExact(size, size)
                    || spores < 0 || waits < 0 || pickaxes < 0 || score < 0 || turn < 0 || producedSpores < 0)
                throw new IllegalArgumentException("Invalid Cooksonia visible content");
        }

        public Cell at(CooksoniaBoard.Pos pos) {
            Objects.requireNonNull(pos);
            if (pos.x() < 0 || pos.y() < 0 || pos.x() >= size || pos.y() >= size)
                throw new IndexOutOfBoundsException("Position outside visible Cooksonia board");
            return cells.get(pos.y() * size + pos.x());
        }
    }

    private static final Cell HIDDEN = new Cell(Optional.empty());

    /** 仅在权威快照变更后投影一次；隐藏地格的类型和湿润状态均不放入返回对象。 */
    public static Content project(CooksoniaGameState source) {
        Objects.requireNonNull(source);
        var cells = new ArrayList<Cell>(source.board().cells().size());
        for (var cell : source.board().cells()) {
            cells.add(cell.revealed()
                    ? new Cell(Optional.of(new KnownGround(cell.terrain(), cell.moist()))) : HIDDEN);
        }
        // 单位／资源记录均不可变且不含地面或整局引用，可安全复用而不暴露隐藏地形。
        return new Content(source.board().size(), cells, source.occupancy().entries(), source.plants(), source.heads(),
                source.gametophytes(), source.spores(), source.waits(), source.pickaxes(),
                source.score(), source.turn(), source.cleared(), source.producedSpores());
    }
}
