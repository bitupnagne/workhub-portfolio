package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.ItemStatus;
import com.portfolio.worksystem.model.ReportItem;
import com.portfolio.worksystem.model.ReportItemDetail;
import com.portfolio.worksystem.model.SearchResult;
import com.portfolio.worksystem.model.TeamWeeklyStatus;
import com.portfolio.worksystem.model.WeeklyReport;
import com.portfolio.worksystem.model.ReportProjectActivity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReportMapper {
    void ensureWeekLock(String weekKey);
    String lockWeek(String weekKey);
    List<ReportProjectActivity> findProjectActivities(@Param("userId") Long userId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    ReportItem findProjectItem(@Param("reportId") Long reportId, @Param("taskId") Long taskId);
    void upsertProjectItem(ReportItem item);
    void updateProjectNote(@Param("itemId") Long itemId, @Param("note") String note);
    void deleteManualItemsByReportId(Long reportId);
    int countSubmittedProjectTeams(@Param("reportId") Long reportId, @Param("weekKey") String weekKey);
    WeeklyReport findReport(@Param("userId") Long userId, @Param("weekKey") String weekKey);
    WeeklyReport findReportById(Long reportId);
    void insertReport(WeeklyReport report);
    int countItemsByReportId(Long reportId);
    Integer maxItemOrder(Long reportId);
    Integer maxItemOrderByStatus(@Param("reportId") Long reportId, @Param("status") ItemStatus status);
    Integer maxTeamItemOrderByStatus(@Param("teamId") Long teamId, @Param("weekKey") String weekKey, @Param("status") ItemStatus status);
    List<ReportItem> findItemsByReportId(Long reportId);
    List<ReportItem> findItemsByReportIdAndStatus(@Param("reportId") Long reportId, @Param("status") ItemStatus status);
    List<ReportItem> findSubmittedItemsByTeamAndStatus(@Param("teamId") Long teamId, @Param("weekKey") String weekKey, @Param("status") ItemStatus status);
    List<ReportItemDetail> findDetailsByItemId(Long itemId);
    ReportItem findItemWithOwner(Long itemId);
    ReportItemDetail findDetailWithOwner(Long detailId);
    void insertItem(ReportItem item);
    void updateItem(ReportItem item);
    void updateItemStatusAndOrder(@Param("itemId") Long itemId, @Param("status") ItemStatus status, @Param("displayOrder") int displayOrder);
    void updateItemOrder(@Param("itemId") Long itemId, @Param("displayOrder") int displayOrder);
    void deleteItem(Long itemId);
    void deleteItemsByReportId(Long reportId);
    Integer maxDetailOrder(Long itemId);
    void insertDetail(ReportItemDetail detail);
    void updateDetail(ReportItemDetail detail);
    void deleteDetail(Long detailId);
    void submitReport(@Param("reportId") Long reportId);
    void recallReport(@Param("reportId") Long reportId);
    void markReportDraft(@Param("reportId") Long reportId);
    TeamWeeklyStatus findTeamStatus(@Param("teamId") Long teamId, @Param("weekKey") String weekKey);
    void upsertTeamStatus(@Param("teamId") Long teamId, @Param("weekKey") String weekKey, @Param("submitted") boolean submitted);
    Boolean isWeekConfirmed(String weekKey);
    void upsertWeekConfirmation(@Param("weekKey") String weekKey, @Param("confirmedBy") Long confirmedBy);
    void cancelWeekConfirmation(String weekKey);
    String findLatestWeekKey();
    List<String> findWeeksWithData();
    List<ReportItem> findSubmittedItemsByTeam(@Param("teamId") Long teamId, @Param("weekKey") String weekKey);
    List<ReportItem> findAdminItems(@Param("weekKey") String weekKey);
    List<SearchResult> search(@Param("query") String query, @Param("weekKey") String weekKey,
                              @Param("teamId") Long teamId, @Param("userId") Long userId);
    void upsertSnapshot(@Param("weekKey") String weekKey, @Param("snapshotHtml") String snapshotHtml, @Param("createdBy") Long createdBy);
    void insertAuditLog(@Param("weekKey") String weekKey, @Param("actorUserId") Long actorUserId,
                        @Param("action") String action, @Param("description") String description);
}
