package com.example.life_sanctuary;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 仅逻辑服务器事件；按服务器实例隔离，退出单人存档后不残留另一世界的玩家数据。 */
public final class CooksoniaLifecycle {
    private static final class Running {
        final CooksoniaServerHost host;
        long tick;
        Running(CooksoniaServerHost host) { this.host = host; }
    }
    private static final Map<MinecraftServer, Running> RUNNING = java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** 未来正式关卡加载器在登录前安装；未安装时保持空目录，不生成演示关卡。 */
    public static void install(MinecraftServer server, CooksoniaLevels levels) {
        checkServer(server);
        Objects.requireNonNull(levels);
        if (RUNNING.containsKey(server)) throw new IllegalStateException("Cooksonia host already initialized for this server");
        RUNNING.put(server, new Running(new CooksoniaServerHost(CooksoniaSavedData.get(server), levels)));
    }

    public static Optional<CooksoniaServerHost> host(MinecraftServer server) {
        checkServer(server);
        var running = RUNNING.get(server);
        return running == null ? Optional.empty() : Optional.of(running.host);
    }

    @SubscribeEvent public void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var server = player.getServer();
        checkServer(server);
        if (!RUNNING.containsKey(server)) install(server, new CooksoniaLevels(List.of()));
        var status = RUNNING.get(server).host.login(player.getUUID());
        if (status == CooksoniaServerHost.LoginStatus.READ_ONLY_ROOT || status == CooksoniaServerHost.LoginStatus.PRESERVED_PLAYER)
            LifeSanctuaryMod.LOGGER.error("Cooksonia data for player {} is preserved and read-only: {}", player.getUUID(), status);
    }

    @SubscribeEvent public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            host(player.getServer()).ifPresent(value -> value.logout(player.getUUID()));
    }

    /** 极快重生可能早于下一次Post检查；末地出口的非死亡重生不关闭棋盘。 */
    @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered() && event.getEntity() instanceof ServerPlayer player)
            host(player.getServer()).ifPresent(value -> value.leaveGame(player.getUUID()));
    }

    @SubscribeEvent public void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        var running = RUNNING.get(server);
        if (running == null) return;
        // 看最终死亡状态，不响应可取消的死亡预事件；其他模组取消死亡时不会误关棋盘。
        for (var player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() && running.host.campaign(player.getUUID()).flatMap(CooksoniaCampaign::active).isPresent())
                running.host.leaveGame(player.getUUID());
        }
        running.tick = Math.addExact(running.tick, 1L);
        running.host.tick(running.tick);
    }

    @SubscribeEvent public void stopping(ServerStoppingEvent event) {
        host(event.getServer()).ifPresent(value -> {
            if (!value.shutdown()) LifeSanctuaryMod.LOGGER.error("Cooksonia shutdown rejected a stale player save; newer stored data preserved");
        });
    }

    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        RUNNING.remove(event.getServer());
    }

    private static void checkServer(MinecraftServer server) {
        Objects.requireNonNull(server);
        if (!server.isSameThread()) throw new IllegalStateException("Cooksonia lifecycle accessed outside the server thread");
    }
}
