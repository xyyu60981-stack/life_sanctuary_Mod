package com.example.life_sanctuary;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 在线玩家唯一宿主；时钟轻量推进，重要纪录立即置脏，单纯计时每200tick合并存储。 */
public final class CooksoniaServerHost {
    public enum LoginStatus { CONNECTED, ALREADY_CONNECTED, READ_ONLY_ROOT, PRESERVED_PLAYER }
    private static final int CLOCK_CHECKPOINT_TICKS = 200;
    private static final class Online {
        final CooksoniaCampaign campaign;
        Optional<CooksoniaSaveCodec.ReadResult> expected;
        int ticksSinceCheckpoint;
        Online(CooksoniaCampaign campaign, Optional<CooksoniaSaveCodec.ReadResult> expected) {
            this.campaign = campaign;
            this.expected = expected;
        }
    }

    private final Thread owningThread = Thread.currentThread();
    private final CooksoniaSavedData storage;
    private final CooksoniaLevels levels;
    private final Map<UUID, Online> online = new HashMap<>();
    private long lastServerTick = -1;

    public CooksoniaServerHost(CooksoniaSavedData storage, CooksoniaLevels levels) {
        this.storage = Objects.requireNonNull(storage);
        this.levels = Objects.requireNonNull(levels);
    }

    public LoginStatus login(UUID playerId) {
        checkThread();
        Objects.requireNonNull(playerId);
        if (online.containsKey(playerId)) return LoginStatus.ALREADY_CONNECTED;
        if (storage.isReadOnly()) return LoginStatus.READ_ONLY_ROOT;
        var saved = storage.read(playerId);
        if (saved.orElse(null) instanceof CooksoniaSaveCodec.Preserved) return LoginStatus.PRESERVED_PLAYER;
        var loaded = saved.isEmpty() ? new CooksoniaSaveCodec.Loaded(CooksoniaProgress.empty(), CooksoniaRewards.empty())
                : (CooksoniaSaveCodec.Loaded) saved.get();
        online.put(playerId, new Online(new CooksoniaCampaign(playerId, levels, loaded.progress(), loaded.rewards()), saved));
        return LoginStatus.CONNECTED;
    }

    /** 仅供可信服务端交互／网络宿主，不按客户端提供的玩家UUID选择。 */
    public Optional<CooksoniaCampaign> campaign(UUID playerId) {
        checkThread();
        var player = online.get(Objects.requireNonNull(playerId));
        return player == null ? Optional.empty() : Optional.of(player.campaign);
    }

    /** 成功动作和领取之后，未来请求宿主应调用此入口立即合入存储。 */
    public boolean checkpoint(UUID playerId) {
        checkThread();
        var player = online.get(Objects.requireNonNull(playerId));
        if (player == null) return false;
        boolean saved = checkpoint(playerId, player);
        if (!saved) {
            player.campaign.leave();
            online.remove(playerId);
        }
        return saved;
    }

    /** 界面退出或玩家确实死亡：只丢棋盘，仍保留在线时钟和宿主。 */
    public boolean leaveGame(UUID playerId) {
        checkThread();
        var player = online.get(Objects.requireNonNull(playerId));
        if (player == null) return false;
        player.campaign.leave();
        boolean saved = checkpoint(playerId, player);
        if (!saved) online.remove(playerId);
        return saved;
    }

    /** 断线时先合入取得的纪录，再释放该玩家全部临时引用。 */
    public boolean logout(UUID playerId) {
        checkThread();
        var player = online.get(Objects.requireNonNull(playerId));
        if (player == null) return false;
        player.campaign.leave();
        boolean saved = checkpoint(playerId, player);
        online.remove(playerId);
        return saved;
    }

    public void tick(long serverTick) {
        checkThread();
        if (serverTick < 0) throw new IllegalArgumentException("Negative Cooksonia host tick sequence");
        if (serverTick <= lastServerTick) return;
        lastServerTick = serverTick;
        var iterator = online.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var player = entry.getValue();
            player.campaign.onServerTick(serverTick);
            player.ticksSinceCheckpoint++;
            if (player.ticksSinceCheckpoint >= CLOCK_CHECKPOINT_TICKS || importantChange(player)) {
                if (!checkpoint(entry.getKey(), player)) {
                    // 旧宿主冲突后停止该实例，不再继续领取或覆盖较新的存档。
                    player.campaign.leave();
                    iterator.remove();
                }
            }
        }
    }

    public boolean shutdown() {
        checkThread();
        boolean saved = true;
        for (var entry : online.entrySet()) {
            entry.getValue().campaign.leave();
            if (!checkpoint(entry.getKey(), entry.getValue())) saved = false;
        }
        online.clear();
        return saved;
    }

    private boolean importantChange(Online player) {
        if (player.expected.isEmpty()) return true;
        var saved = (CooksoniaSaveCodec.Loaded) player.expected.get();
        var rewards = player.campaign.rewards();
        return saved.progress() != player.campaign.progress() || saved.rewards().epoch() != rewards.epoch()
                || saved.rewards().earnedEpochs() != rewards.earnedEpochs() || saved.rewards().pending() != rewards.pending();
    }

    private boolean checkpoint(UUID playerId, Online player) {
        var next = new CooksoniaSaveCodec.Loaded(player.campaign.progress(), player.campaign.rewards());
        if (!storage.update(playerId, player.expected, next)) return false;
        player.expected = storage.read(playerId);
        player.ticksSinceCheckpoint = 0;
        return true;
    }

    private void checkThread() {
        if (Thread.currentThread() != owningThread) throw new IllegalStateException("Cooksonia host accessed outside its owning server thread");
    }
}
