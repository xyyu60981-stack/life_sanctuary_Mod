package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在线奖励资格与待领取凭据；不包含局内棋盘，也不在没有奖励配置时猜测物品。 */
public final class CooksoniaRewards {
    public static final int PERIOD_TICKS = 24000;
    private final long epoch;
    private final int onlineTicks;
    private final Map<String, Long> earnedEpochs;
    private final Set<Ticket> pending;

    /** 所属玩家由 Campaign 绑定；关卡和周期共同标识一份资格，不能由客户端生成奖励内容。 */
    public record Ticket(String levelId, long epoch) {
        public Ticket {
            CooksoniaProgress.requireId(levelId);
            if (epoch < 0) throw new IllegalArgumentException("Negative Cooksonia reward epoch");
        }
    }

    public CooksoniaRewards(long epoch, int onlineTicks, Map<String, Long> earnedEpochs, Set<Ticket> pending) {
        this(epoch, onlineTicks, Map.copyOf(earnedEpochs), Set.copyOf(pending), false);
    }

    private CooksoniaRewards(long epoch, int onlineTicks, Map<String, Long> earnedEpochs, Set<Ticket> pending, boolean trustedClockStep) {
        if (epoch < 0 || onlineTicks < 0 || onlineTicks >= PERIOD_TICKS)
            throw new IllegalArgumentException("Invalid Cooksonia online reward clock");
        if (!trustedClockStep) {
            for (var entry : earnedEpochs.entrySet()) {
                CooksoniaProgress.requireId(entry.getKey());
                if (entry.getValue() < 0 || entry.getValue() > epoch)
                    throw new IllegalArgumentException("Invalid earned Cooksonia reward epoch");
            }
            for (var ticket : pending) {
                var earned = earnedEpochs.get(ticket.levelId());
                if (ticket.epoch() > epoch || earned == null || ticket.epoch() > earned)
                    throw new IllegalArgumentException("Pending Cooksonia reward has no earned entitlement");
            }
        }
        this.epoch = epoch;
        this.onlineTicks = onlineTicks;
        this.earnedEpochs = earnedEpochs;
        this.pending = pending;
    }

    public long epoch() { return epoch; }
    public int onlineTicks() { return onlineTicks; }
    public Map<String, Long> earnedEpochs() { return earnedEpochs; }
    public Set<Ticket> pending() { return pending; }

    public static CooksoniaRewards empty() { return new CooksoniaRewards(0L, 0, Map.of(), Set.of()); }

    /** 仅由在线玩家的真实服务器 tick 调用一次；没有时间差补算或离线追赶。 */
    CooksoniaRewards tick() {
        return onlineTicks + 1 == PERIOD_TICKS
                ? new CooksoniaRewards(Math.addExact(epoch, 1L), 0, earnedEpochs, pending, true)
                : new CooksoniaRewards(epoch, onlineTicks + 1, earnedEpochs, pending, true);
    }

    /** 只接收本轮新发生的一局首次通关；旧的通关旗标不能每轮自动兑现。 */
    CooksoniaRewards earn(String levelId) {
        CooksoniaProgress.requireId(levelId);
        if (Objects.equals(earnedEpochs.get(levelId), epoch)) return this;
        var earned = new HashMap<>(earnedEpochs);
        earned.put(levelId, epoch);
        var waiting = new HashSet<>(pending);
        waiting.add(new Ticket(levelId, epoch));
        return new CooksoniaRewards(epoch, onlineTicks, earned, waiting);
    }

    /** 仅用于构造交付成功后的提案；earnedEpochs 保留，领取后不能再次获得同轮资格。 */
    CooksoniaRewards claimed(Ticket ticket) {
        Objects.requireNonNull(ticket);
        if (!pending.contains(ticket)) throw new IllegalArgumentException("Unknown pending Cooksonia reward");
        var waiting = new HashSet<>(pending);
        waiting.remove(ticket);
        return new CooksoniaRewards(epoch, onlineTicks, earnedEpochs, waiting);
    }
}
