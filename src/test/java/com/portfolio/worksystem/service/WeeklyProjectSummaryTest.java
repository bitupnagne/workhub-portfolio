package com.portfolio.worksystem.service;

import com.portfolio.worksystem.model.ItemStatus;
import com.portfolio.worksystem.model.ReportProjectActivity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeeklyProjectSummaryTest {
    @Test
    void completedAtCreationByAssigneeCountsAsDone() {
        var result = WeeklyProjectSummary.summarize(List.of(activity(1, "CREATED_COMPLETED", "DONE")));
        assertEquals(ItemStatus.DONE, result.getFirst().getStatus());
    }

    @Test
    void assignmentOrCommentOnCompletedTaskIsNotClaimedAsCompletion() {
        assertEquals(ItemStatus.NEW, WeeklyProjectSummary.summarize(List.of(activity(1, "ASSIGNED", "DONE"))).getFirst().getStatus());
        assertEquals(ItemStatus.CONTINUE, WeeklyProjectSummary.summarize(List.of(activity(1, "COMMENTED", "DONE"))).getFirst().getStatus());
    }

    @Test
    void reopenedWorkKeepsLatestStatusAndChronologicalEvidence() {
        var done = activity(1, "STATUS_CHANGED", "DONE");
        var reopened = activity(2, "STATUS_CHANGED", "IN_PROGRESS");
        var result = WeeklyProjectSummary.summarize(List.of(reopened, done)).getFirst();
        assertEquals(ItemStatus.CONTINUE, result.getStatus());
        assertEquals(2, result.getSourceActivityId());
        assertTrue(result.getProjectSummary().indexOf("기록 1") < result.getProjectSummary().indexOf("기록 2"));
    }

    private ReportProjectActivity activity(long id, String type, String status) {
        var activity = new ReportProjectActivity();
        activity.setId(id); activity.setProjectId(1L); activity.setTaskId(2L);
        activity.setReportUserId(3L); activity.setReportTeamId(4L);
        activity.setProjectName("샘플 프로젝트"); activity.setTaskTitle("샘플 업무");
        activity.setEventType(type); activity.setTaskStatus(status);
        activity.setProgress("DONE".equals(status) ? 100 : 50);
        activity.setSummary("기록 " + id);
        activity.setOccurredAt(LocalDateTime.of(2026, 11, 2, 9, 0).plusHours(id));
        return activity;
    }
}
