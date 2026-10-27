package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 固定关卡模板与开局工厂；模板不持有玩家、会话或可复用的单位身份。 */
public record CooksoniaLevel(CooksoniaBoard board, List<StartingPlant> startingPlants,
        Resources resources, long threshold, CooksoniaTurnResolver.Rules turnRules,
        CooksoniaWaterPlayback.Timing waterTiming) {

    /** 初始头无朝向；数量、位置、类型和资源均由关卡明确给出。 */
    public record StartingPlant(CooksoniaBoard.Pos pos, CooksoniaPlant.Kind kind,
            int actions, int branches, int health) {
        public StartingPlant {
            Objects.requireNonNull(pos);
            Objects.requireNonNull(kind);
            if (actions < 0 || branches < 0 || health <= 0)
                throw new IllegalArgumentException("Invalid starting Cooksonia plant resources");
        }
    }

    public record Resources(int spores, int waits, int pickaxes) {
        public Resources {
            if (spores < 0 || waits < 0 || pickaxes < 0)
                throw new IllegalArgumentException("Negative initial Cooksonia resources");
        }
    }

    public CooksoniaLevel {
        Objects.requireNonNull(board);
        startingPlants = List.copyOf(startingPlants);
        Objects.requireNonNull(resources);
        Objects.requireNonNull(turnRules);
        Objects.requireNonNull(waterTiming);
        if (threshold < 0 || startingPlants.isEmpty())
            throw new IllegalArgumentException("A Cooksonia level needs a nonnegative threshold and starting plants");
        // 可播种寒冷地面产生冻原株；提前检查，不能到玩家萌发时才暴露配置错误。
        if (turnRules.births().actions() < 1 && board.cells().stream()
                .anyMatch(cell -> cell.terrain() == CooksoniaBoard.Terrain.COLD))
            throw new IllegalArgumentException("Cooksonia level must support tundra germination actions");
        var occupied = new HashSet<CooksoniaBoard.Pos>();
        for (var plant : startingPlants) {
            if (!board.contains(plant.pos()) || !occupied.add(plant.pos()))
                throw new IllegalArgumentException("Starting Cooksonia plants overlap or lie outside the board");
            var ground = board.at(plant.pos());
            // 初始摆盘不执行玩家落点：不猜测开局伤害、免费泥炭奖励或额外揭示。
            if (!ground.revealed() || ground.terrain() != CooksoniaBoard.Terrain.NORMAL
                    && ground.terrain() != CooksoniaBoard.Terrain.COLD && ground.terrain() != CooksoniaBoard.Terrain.DRY)
                throw new IllegalArgumentException("Starting heads require revealed ordinary or climate ground");
        }
        for (int y = 0; y < board.size(); y++) {
            for (int x = 0; x < board.size(); x++) {
                var pos = new CooksoniaBoard.Pos(x, y);
                if (board.at(pos).terrain() != CooksoniaBoard.Terrain.ONE_SHOT_WATER) continue;
                for (var neighbor : board.cardinalNeighbors(pos)) {
                    if (board.at(neighbor).terrain() != CooksoniaBoard.Terrain.COBBLESTONE)
                        throw new IllegalArgumentException("Initial one-shot water must be enclosed by cobblestone or board edges");
                }
            }
        }
        // 仅初始化一次河流四邻；后续每个动作与 tick 均不重扫模板。
        board = CooksoniaRiverMoisture.index(board).apply(board);
    }

    /** 每次挑战重新分配全部身份；共享的地面和模板不可变，不会继承上一局资源或成熟历史。 */
    public CooksoniaGameState initialState() {
        var plants = new HashMap<UUID, CooksoniaPlant>();
        var heads = new HashMap<UUID, CooksoniaHead>();
        var entries = new HashMap<CooksoniaBoard.Pos, CooksoniaOccupancy.Entry>();
        var identities = new HashSet<UUID>();
        for (var start : startingPlants) {
            UUID plantId = freshId(identities);
            UUID headId = freshId(identities);
            plants.put(plantId, new CooksoniaPlant(plantId, start.kind(), start.actions(), start.branches(), false));
            heads.put(headId, CooksoniaHead.seeded(headId, plantId, start.pos(), start.health()));
            entries.put(start.pos(), new CooksoniaOccupancy.Entry(headId, plantId, CooksoniaOccupancy.Kind.HEAD));
        }
        return new CooksoniaGameState(board, new CooksoniaOccupancy(board.size(), entries), plants, heads, Map.of(),
                resources.spores(), resources.waits(), resources.pickaxes(), 0L, 0L, false, 0);
    }

    /** 由未来宿主在服务器线程、检查关卡解锁后调用；不接受客户端提供的会话身份。 */
    public CooksoniaSession open(UUID playerId) {
        Objects.requireNonNull(playerId);
        return new CooksoniaSession(playerId, UUID.randomUUID(), initialState(), threshold, waterTiming, turnRules);
    }

    private static UUID freshId(java.util.Set<UUID> identities) {
        UUID id;
        do { id = UUID.randomUUID(); } while (!identities.add(id));
        return id;
    }
}
