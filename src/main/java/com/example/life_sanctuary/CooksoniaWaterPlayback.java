package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 只包含可见路径与服务器播放进度，不持有水路 Plan、隐藏地形或权威棋盘。 */
public final class CooksoniaWaterPlayback {
    /** 表现参数由后续关卡／UI 配置明确提供；不预设每格动画和消散时长。 */
    public record Timing(int cellTicks, int fadeTicks) {
        public Timing {
            if (cellTicks <= 0 || fadeTicks < 0) throw new IllegalArgumentException("Invalid water playback timing");
            Math.addExact(Math.multiplyExact(CooksoniaOneShotWater.MAX_PATH_CELLS, cellTicks), fadeTicks);
        }
        public int duration(int cells) {
            if (cells <= 0 || cells > CooksoniaOneShotWater.MAX_PATH_CELLS)
                throw new IllegalArgumentException("Invalid water playback path length");
            return Math.addExact(Math.multiplyExact(cells, cellTicks), fadeTicks);
        }
    }

    private final List<List<CooksoniaBoard.Pos>> paths;
    private final Timing timing;
    private final int totalTicks;
    private final int remainingTicks;

    private CooksoniaWaterPlayback(List<List<CooksoniaBoard.Pos>> paths, Timing timing, int totalTicks, int remainingTicks) {
        this.paths = paths;
        this.timing = timing;
        this.totalTicks = totalTicks;
        this.remainingTicks = remainingTicks;
    }

    public static CooksoniaWaterPlayback start(List<CooksoniaOneShotWater.Plan> flows, Timing timing) {
        Objects.requireNonNull(timing);
        if (flows.isEmpty()) throw new IllegalArgumentException("No water flows to play");
        var paths = new ArrayList<List<CooksoniaBoard.Pos>>();
        int duration = 0;
        for (var flow : flows) {
            paths.add(List.copyOf(flow.path()));
            duration = Math.max(duration, timing.duration(flow.path().size()));
        }
        return new CooksoniaWaterPlayback(List.copyOf(paths), timing, duration, duration);
    }

    /** 一次实际服务器 tick；只创建轻量进度外壳，路径集合直接复用。 */
    public Optional<CooksoniaWaterPlayback> advance() {
        return remainingTicks == 1 ? Optional.empty()
                : Optional.of(new CooksoniaWaterPlayback(paths, timing, totalTicks, remainingTicks - 1));
    }

    public List<List<CooksoniaBoard.Pos>> paths() { return paths; }
    public Timing timing() { return timing; }
    public int totalTicks() { return totalTicks; }
    public int remainingTicks() { return remainingTicks; }
    public int elapsedTicks() { return totalTicks - remainingTicks; }
}
