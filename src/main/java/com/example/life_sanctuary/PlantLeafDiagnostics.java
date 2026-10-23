package com.example.life_sanctuary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 按需生成的服务器诊断，不用于逐帧悬停；悬停仍使用 PlantLeafStatus 的轻量汇总。
 * 不读世界、不抽随机、不运行处理器，也不将“结构存在”解释为可以退款或重试。
 */
public final class PlantLeafDiagnostics {
    private PlantLeafDiagnostics() {}

    public record Task(PlantLeafHandlers.Event event, PlantLeafTimer timer) {
        public Task {
            java.util.Objects.requireNonNull(event);
            java.util.Objects.requireNonNull(timer);
            if (event.kind() != timer.kind()) throw new IllegalArgumentException("Leaf task kind mismatch");
        }
    }

    /** reasons 保留全部暂停原因，失败任务按种类／叶键排序，便于前后诊断对比。 */
    public record Report(PlantIndividualState individual, PlantLeafStatus.Snapshot status,
            Set<String> reasons, List<Task> failedTasks, boolean missingLeafHandler,
            boolean missingWitheringHandler, boolean inventoryOutcomeUncertain) {
        public Report {
            java.util.Objects.requireNonNull(individual);
            java.util.Objects.requireNonNull(status);
            reasons = Set.copyOf(reasons);
            failedTasks = List.copyOf(failedTasks);
        }
    }

    public record Lookup(PlantLeafStatus.LookupReason reason, Optional<Report> report) {}

    /** 调用方须位于所属服务器线程。仅遍历当前任务，不扫描历史叶或全世界个体。 */
    public static Lookup byId(PlantIndividuals store, UUID id) {
        var lookup = PlantLeafStatus.byId(store, id);
        if (lookup.snapshot().isEmpty()) return new Lookup(lookup.reason(), Optional.empty());
        var individual = store.find(id).orElse(null);
        var data = store.leavesOf(id).orElse(null);
        if (individual == null || data == null)
            return new Lookup(PlantLeafStatus.LookupReason.DATA_UNAVAILABLE, Optional.empty());
        var status = lookup.snapshot().get();
        List<Task> failed = new ArrayList<>();
        data.timers().regrowth().filter(PlantLeafTimer::failed).ifPresent(timer ->
                failed.add(new Task(new PlantLeafHandlers.Event(PlantLeafTimer.Kind.REGROWTH, ""), timer)));
        data.timers().shedding().forEach((key, timer) -> {
            if (timer.failed()) failed.add(new Task(new PlantLeafHandlers.Event(PlantLeafTimer.Kind.SHEDDING, key), timer));
        });
        failed.sort(Comparator.comparing((Task task) -> task.event().kind().name())
                .thenComparing(task -> task.event().leafKey()));
        boolean hasLeafTasks = data.timers().regrowth().isPresent() || !data.timers().shedding().isEmpty();
        var report = new Report(individual, status, individual.environmentReasons(), failed,
                hasLeafTasks && !status.hasHandler(),
                status.witheringTimer().isPresent() && !status.hasWitheringHandler(),
                individual.environmentReasons().contains("leaf_collection_error"));
        return new Lookup(PlantLeafStatus.LookupReason.FOUND, Optional.of(report));
    }
}
