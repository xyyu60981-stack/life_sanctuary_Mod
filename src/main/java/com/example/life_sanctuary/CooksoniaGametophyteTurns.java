package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 配子体单回合提案；只读操作开始前的单位集合和落点／水路处理后的地面。 */
public final class CooksoniaGametophyteTurns {
    private CooksoniaGametophyteTurns() {}

    /**
     * 尚未生成新头或删除枯死占位，不能作为完成后的整局快照写回。
     * 私有构造确保进度和到期集合来自同一次计算；执行时核对原快照身份。
     */
    public static final class Plan {
        private final CooksoniaGameState source;
        private final CooksoniaBoard ground;
        private final long nextTurn;
        private final Map<UUID, CooksoniaGametophyte> advanced;
        private final Set<UUID> growing;
        private final Set<UUID> withering;

        private Plan(CooksoniaGameState source, CooksoniaBoard ground, long nextTurn,
                Map<UUID, CooksoniaGametophyte> advanced, Set<UUID> growing, Set<UUID> withering) {
            this.source = source;
            this.ground = ground;
            this.nextTurn = nextTurn;
            this.advanced = Map.copyOf(advanced);
            this.growing = Set.copyOf(growing);
            this.withering = Set.copyOf(withering);
        }

        public boolean matches(CooksoniaGameState current) { return source == current; }
        public CooksoniaBoard ground() { return ground; }
        public long nextTurn() { return nextTurn; }
        public Map<UUID, CooksoniaGametophyte> advanced() { return advanced; }
        public Set<UUID> growing() { return growing; }
        public Set<UUID> withering() { return withering; }
    }

    /**
     * 移动／等待才调用一次。ground 是本次落点和补水完成后的地面；只推进 source 中原有配子体。
     * 本次动作新建的单位由外层结算另行合入，不能先放进 source 再调用以免多长一回合。
     */
    public static Plan plan(CooksoniaGameState source, CooksoniaBoard ground) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(ground);
        if (source.board().size() != ground.size())
            throw new IllegalArgumentException("Cannot resize board during a Cooksonia turn");
        long nextTurn = Math.addExact(source.turn(), 1L);
        var advanced = new HashMap<UUID, CooksoniaGametophyte>();
        var growing = new HashSet<UUID>();
        var withering = new HashSet<UUID>();
        for (var current : source.gametophytes().values()) {
            var next = current;
            // 到期者等待外层完成萌发／删除，不再累计或因后来的补水而复活。
            if (!current.readyToGrow() && !current.readyToWither()) {
                boolean dry = ground.at(current.pos()).terrain() == CooksoniaBoard.Terrain.DRY;
                next = new CooksoniaGametophyte(current.id(), current.futurePlantId(), current.pos(),
                        current.kind(), current.bornTurn(),
                        dry ? current.growthTurns() : Math.min(2, current.growthTurns() + 1),
                        dry ? Math.min(2, current.dryTurns() + 1) : 0, current.peatBonus());
            }
            advanced.put(next.id(), next);
            if (next.readyToGrow()) growing.add(next.id());
            if (next.readyToWither()) withering.add(next.id());
        }
        return new Plan(source, ground, nextTurn, advanced, growing, withering);
    }
}
