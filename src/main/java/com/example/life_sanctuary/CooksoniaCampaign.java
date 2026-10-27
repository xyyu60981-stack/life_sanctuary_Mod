package com.example.life_sanctuary;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 单个玩家的服务器进度宿主底座；持久化层读取 progress／rewards，临时棋盘只存在于 active 中。 */
public final class CooksoniaCampaign {
    public enum StartStatus { STARTED, UNKNOWN_LEVEL, LOCKED_LEVEL }
    public enum ClaimStatus { CLAIMED, WRONG_PLAYER, NOT_PENDING, RETAINED }

    /**
     * 可信服务器库存适配器：必须整份原子交付；满背包／配置不可用返回 false，不能部分塞入或扔地。
     * 未配置奖励物品时不能返回成功；此接口不接受客户端的物品或数量。
     */
    @FunctionalInterface
    public interface RewardDelivery { boolean deliver(CooksoniaRewards.Ticket ticket); }

    private final Thread owningThread = Thread.currentThread();
    private final UUID playerId;
    private final CooksoniaLevels levels;
    private CooksoniaProgress progress;
    private CooksoniaSession active;
    private String activeLevel;
    private CooksoniaRewards rewards;
    private boolean clearObserved;
    private boolean delivering;
    private long lastServerTick = -1;

    /** restored 是服务器持久化层的可信数据，不能来自客户端上传。 */
    public CooksoniaCampaign(UUID playerId, CooksoniaLevels levels, CooksoniaProgress restored) {
        this(playerId, levels, restored, CooksoniaRewards.empty());
    }

    public CooksoniaCampaign(UUID playerId, CooksoniaLevels levels, CooksoniaProgress restored,
            CooksoniaRewards restoredRewards) {
        this.playerId = Objects.requireNonNull(playerId);
        this.levels = Objects.requireNonNull(levels);
        this.progress = Objects.requireNonNull(restored);
        this.rewards = Objects.requireNonNull(restoredRewards);
    }

    public CooksoniaRewards rewards() {
        checkThread();
        collect();
        return rewards;
    }

    /** 先采集上一次操作，再推进在线周期，避免旧局通关在新周期被误当成重新通关。 */
    public void onServerTick(long serverTick) {
        checkThread();
        if (serverTick < 0) throw new IllegalArgumentException("Negative server tick sequence");
        if (serverTick <= lastServerTick) return;
        collect();
        var nextRewards = rewards.tick();
        if (active != null) active.onServerTick(serverTick);
        rewards = nextRewards;
        lastServerTick = serverTick;
    }

    /** 背包满、配置缺失或适配器失败都保留凭据；真实库存实现尚待游戏宿主接入。 */
    public ClaimStatus claim(UUID actingPlayer, CooksoniaRewards.Ticket ticket, RewardDelivery delivery) {
        checkThread();
        Objects.requireNonNull(ticket);
        Objects.requireNonNull(delivery);
        if (!playerId.equals(actingPlayer)) return ClaimStatus.WRONG_PLAYER;
        collect();
        if (!rewards.pending().contains(ticket)) return ClaimStatus.NOT_PENDING;
        var afterDelivery = rewards.claimed(ticket);
        boolean delivered;
        delivering = true;
        try { delivered = delivery.deliver(ticket); }
        finally { delivering = false; }
        if (!delivered) return ClaimStatus.RETAINED;
        rewards = afterDelivery;
        return ClaimStatus.CLAIMED;
    }

    /** 只从仍由宿主持有的会话快照采集；客户端没有设置分数／通关的入口。 */
    public CooksoniaProgress progress() {
        checkThread();
        collect();
        return progress;
    }

    /** 供未来网络宿主派发五种动作；动作后的存档／界面读取 progress 即合入新纪录。 */
    public Optional<CooksoniaSession> active() {
        checkThread();
        return Optional.ofNullable(active);
    }

    public boolean unlocked(String id) {
        checkThread();
        collect();
        return levels.unlocked(progress, id);
    }

    /** 拒绝未知或未解锁关卡不会关掉当前对局；重开则先保留进度，再释放旧棋盘。 */
    public StartStatus start(String id) {
        checkThread();
        collect();
        var entry = levels.find(id);
        if (entry.isEmpty()) return StartStatus.UNKNOWN_LEVEL;
        if (!levels.unlocked(progress, id)) return StartStatus.LOCKED_LEVEL;
        // 新局构建失败时，仍保留当前会话；不会先丢弃现有棋盘。
        var next = entry.get().level().open(playerId);
        // 规则由服务器目录决定；每次工具操作读取最新权威进度，旧对局也能反映刚取得的解锁。
        next.toolAccess(tool -> levels.tools().allows(tool, progress()));
        if (active != null) active.close();
        active = next;
        activeLevel = id;
        clearObserved = false;
        return StartStatus.STARTED;
    }

    /** 退出、死亡、断线必须先调用这里；保留取得的分数／通关，丢弃本局其他内容。 */
    public void leave() {
        checkThread();
        collect();
        if (active != null) active.close();
        active = null;
        activeLevel = null;
        clearObserved = false;
    }

    private void collect() {
        if (active == null) return;
        var snapshot = active.snapshot();
        if (snapshot.isEmpty()) return;
        var state = snapshot.get();
        var nextProgress = progress.observe(activeLevel, state.score(), state.cleared());
        var nextRewards = state.cleared() && !clearObserved ? rewards.earn(activeLevel) : rewards;
        progress = nextProgress;
        rewards = nextRewards;
        if (state.cleared()) clearObserved = true;
    }

    private void checkThread() {
        if (Thread.currentThread() != owningThread)
            throw new IllegalStateException("Cooksonia campaign accessed outside its owning server thread");
        if (delivering) throw new IllegalStateException("Reentrant Cooksonia reward delivery");
    }
}
