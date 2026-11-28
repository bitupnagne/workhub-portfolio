package com.portfolio.worksystem.service;

import com.portfolio.worksystem.model.ItemStatus;
import com.portfolio.worksystem.model.ReportItem;
import com.portfolio.worksystem.model.ReportProjectActivity;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 주간 활동의 스냅샷만 사용한다. 현재 담당자나 현재 업무 상태로 과거 보고를 재구성하지 않는다. */
final class WeeklyProjectSummary {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM/dd");

    private WeeklyProjectSummary() { }

    static List<ReportItem> summarize(List<ReportProjectActivity> events) {
        Map<Long, List<ReportProjectActivity>> byTask = new LinkedHashMap<>();
        events.stream().sorted(Comparator.comparing(ReportProjectActivity::getOccurredAt)
                        .thenComparing(ReportProjectActivity::getId))
                .forEach(event -> byTask.computeIfAbsent(event.getTaskId(), ignored -> new ArrayList<>()).add(event));
        List<ReportItem> items = new ArrayList<>();
        byTask.forEach((taskId, taskEvents) -> {
            ReportProjectActivity first = taskEvents.getFirst();
            ReportProjectActivity latest = taskEvents.getLast();
            ReportItem item = new ReportItem();
            item.setSourceTaskId(taskId);
            item.setSourceProjectId(latest.getProjectId());
            item.setSourceProjectName(latest.getProjectName());
            item.setSourceTeamId(first.getReportTeamId());
            item.setSourceActivityId(latest.getId());
            item.setContent(latest.getTaskTitle());
            item.setProjectProgress(latest.getProgress());
            item.setDueDate(latest.getDueDate());
            // 완료 상태의 업무에 댓글만 달아도 자신의 완료 실적으로 표시되는 것을 막는다.
            boolean completed = taskEvents.stream().anyMatch(event -> ("STATUS_CHANGED".equals(event.getEventType())
                    || "CREATED_COMPLETED".equals(event.getEventType()))
                    && "DONE".equals(event.getTaskStatus()));
            boolean created = taskEvents.stream().anyMatch(event -> "CREATED".equals(event.getEventType())
                    || "CREATED_COMPLETED".equals(event.getEventType())
                    || "ASSIGNED".equals(event.getEventType()) || "HANDOVER_ACCEPTED".equals(event.getEventType()));
            item.setStatus(completed && "DONE".equals(latest.getTaskStatus()) ? ItemStatus.DONE
                    : created ? ItemStatus.NEW : ItemStatus.CONTINUE);
            item.setCycle("프로젝트");
            // 본문이 길어져도 원본 활동은 모두 프로젝트 상세에 남으며 보고서에는 최근 12건을 요약한다.
            int offset = Math.max(0, taskEvents.size() - 12);
            List<String> lines = new ArrayList<>();
            if (offset > 0) lines.add("이전 활동 " + offset + "건 · 프로젝트에서 활동 기록 확인");
            for (ReportProjectActivity event : taskEvents.subList(offset, taskEvents.size())) {
                String summary = event.getSummary() == null ? "업무 활동 기록" : event.getSummary().strip();
                if (summary.length() > 500) summary = summary.substring(0, 500) + "…";
                lines.add(DAY.format(event.getOccurredAt()) + " · " + summary);
            }
            item.setProjectSummary(String.join("\n", lines));
            items.add(item);
        });
        return items;
    }
}
