package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.ReportDetailForm;
import com.portfolio.worksystem.dto.ReportItemBundleForm;
import com.portfolio.worksystem.dto.ReportItemForm;
import com.portfolio.worksystem.mapper.ReportMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.ItemStatus;
import com.portfolio.worksystem.model.MemberStatus;
import com.portfolio.worksystem.model.ReportItem;
import com.portfolio.worksystem.model.ReportItemDetail;
import com.portfolio.worksystem.model.SearchResult;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.TeamWeeklyStatus;
import com.portfolio.worksystem.model.WeeklyReport;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@RequiredArgsConstructor
@Service
public class ReportService {
    private final ReportMapper reportMapper;
    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final OrganizationService organizationService;
    private final HrService hrService;

    public String defaultWeekKey() {
        return WeekUtils.getCurrentWeekKey();
    }

    public List<String> weeksWithData() {
        return reportMapper.findWeeksWithData();
    }

    public String latestWeekKey() {
        return reportMapper.findLatestWeekKey();
    }

    @Transactional(readOnly = true)
    public WeeklyReport findMyReport(Long userId, String weekKey) {
        return reportMapper.findReport(userId, weekKey);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WeeklyReport getOrCreateReport(Long userId, String weekKey) {
        // 잘못된 주차 형식(예: "undefined")으로 보고서/항목이 생성되면 weeksWithData가 오염되고
        // getWeekLabel이 예외를 던져 편집 화면이 깨진다 → 생성 지점에서 형식을 강제 검증.
        if (!WeekUtils.isValidWeekKey(weekKey)) {
            throw new BusinessException("올바르지 않은 주차입니다.");
        }
        lockWeek(weekKey);
        WeeklyReport report = reportMapper.findReport(userId, weekKey);
        if (report != null) {
            return report;
        }
        report = new WeeklyReport();
        report.setUserId(userId);
        report.setWeekKey(weekKey);
        report.setSubmitted(false);
        reportMapper.insertReport(report);
        return report;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void syncProjectActivities(CustomUserDetails currentUser, String weekKey) {
        lockWeek(weekKey);
        WeeklyReport report = getOrCreateReport(currentUser.getUserId(), weekKey);
        if (Boolean.TRUE.equals(report.getSubmitted()) || isWeekConfirmed(weekKey)
                || isTeamSubmitted(currentUser.getTeamId(), weekKey)) return;

        var start = WeekUtils.getWeekStart(weekKey).atStartOfDay();
        var items = WeeklyProjectSummary.summarize(reportMapper.findProjectActivities(
                currentUser.getUserId(), start, start.plusWeeks(1)));
        for (ReportItem item : items) {
            ReportItem existing = reportMapper.findProjectItem(report.getId(), item.getSourceTaskId());
            Long sourceTeamId = existing == null ? item.getSourceTeamId() : existing.getSourceTeamId();
            if (isTeamSubmitted(sourceTeamId, weekKey)) continue;
            if (existing != null && java.util.Objects.equals(existing.getSourceActivityId(), item.getSourceActivityId())) continue;
            item.setWeeklyReportId(report.getId());
            item.setDisplayOrder(existing == null
                    ? reportMapper.maxItemOrderByStatus(report.getId(), item.getStatus()) + 1 : existing.getDisplayOrder());
            reportMapper.upsertProjectItem(item);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveProjectNote(CustomUserDetails currentUser, Long itemId, String note) {
        ReportItem item = reportMapper.findItemWithOwner(itemId);
        validateItemOwner(item, currentUser);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        if (!item.isProjectLinked()) throw new BusinessException("프로젝트 연계 항목을 찾을 수 없습니다.");
        if (isTeamSubmitted(item.getSourceTeamId(), itemWeekKey(item)))
            throw new BusinessException("이 업무가 포함된 팀 보고가 제출되어 수정할 수 없습니다.");
        if (note != null && note.length() > 2000) throw new BusinessException("보충 설명은 2,000자 이내로 입력해 주세요.");
        reportMapper.updateProjectNote(itemId, trimToNull(note));
    }

    @Transactional(readOnly = true)
    public Map<ItemStatus, List<ReportItem>> myItems(Long userId, String weekKey) {
        WeeklyReport report = reportMapper.findReport(userId, weekKey);
        return groupItems(report == null ? List.of() : attachDetails(reportMapper.findItemsByReportId(report.getId())));
    }

    @Transactional(readOnly = true)
    public List<ReportItem> myFlatItems(Long userId, String weekKey) {
        WeeklyReport report = reportMapper.findReport(userId, weekKey);
        return report == null ? List.of() : attachDetails(reportMapper.findItemsByReportId(report.getId()));
    }

    @Transactional(readOnly = true)
    public Map<ItemStatus, List<ReportItem>> teamItems(Long teamId, String weekKey) {
        return groupItems(attachDetails(reportMapper.findSubmittedItemsByTeam(teamId, weekKey)));
    }

    @Transactional(readOnly = true)
    public Map<Team, Map<ItemStatus, List<ReportItem>>> adminItems(String weekKey) {
        List<Team> teams = teamMapper.findAll().stream()
                .filter(t -> !Boolean.FALSE.equals(t.getWeeklyReportTarget()))
                .toList();
        Map<Team, Map<ItemStatus, List<ReportItem>>> result = new LinkedHashMap<>();
        List<ReportItem> items = attachDetails(reportMapper.findAdminItems(weekKey));
        for (Team team : teams) {
            List<ReportItem> teamItems = items.stream()
                    .filter(item -> team.getId().equals(item.getTeamId()))
                    .toList();
            if (!teamItems.isEmpty()) {
                result.put(team, groupItems(teamItems));
            }
        }
        return result;
    }

    public List<MemberStatus> memberStatuses(Long teamId, String weekKey) {
        return userMapper.findMemberStatuses(teamId, weekKey);
    }

    public TeamWeeklyStatus teamStatus(Long teamId, String weekKey) {
        return reportMapper.findTeamStatus(teamId, weekKey);
    }

    public boolean isWeekConfirmed(String weekKey) {
        return Boolean.TRUE.equals(reportMapper.isWeekConfirmed(weekKey));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveItem(ReportItemForm form, CustomUserDetails currentUser) {
        validateOwnWritableReport(currentUser, form.weekKey());
        WeeklyReport report = getOrCreateReport(currentUser.getUserId(), form.weekKey());
        if (form.id() == null) {
            ReportItem item = new ReportItem();
            item.setWeeklyReportId(report.getId());
            item.setContent(form.content().trim());
            item.setDueDate(WeekUtils.enforceWeekday(form.dueDate()));
            item.setCycle(trimToNull(form.cycle()));
            item.setStatus(form.status());
            item.setDisplayOrder(reportMapper.maxItemOrderByStatus(report.getId(), form.status()) + 1);
            reportMapper.insertItem(item);
            return;
        }
        ReportItem item = reportMapper.findItemWithOwner(form.id());
        validateItemOwner(item, currentUser);
        requireManualItem(item);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        item.setContent(form.content().trim());
        item.setDueDate(WeekUtils.enforceWeekday(form.dueDate()));
        item.setCycle(trimToNull(form.cycle()));
        item.setStatus(form.status());
        reportMapper.updateItem(item);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveItemBundle(ReportItemBundleForm form, CustomUserDetails currentUser) {
        if (form.getStatus() == null || form.getWeekKey() == null || form.getWeekKey().isBlank()) {
            throw new BusinessException("항목 정보를 확인해 주세요.");
        }
        if (form.getContent() == null || form.getContent().isBlank()) {
            throw new BusinessException("추진 내용을 입력하세요.");
        }

        ReportItem item;
        if (form.getId() == null) {
            validateOwnWritableReport(currentUser, form.getWeekKey());
            WeeklyReport report = getOrCreateReport(currentUser.getUserId(), form.getWeekKey());
            item = new ReportItem();
            item.setWeeklyReportId(report.getId());
            item.setStatus(form.getStatus());
            item.setDisplayOrder(reportMapper.maxItemOrderByStatus(report.getId(), form.getStatus()) + 1);
        } else {
            item = reportMapper.findItemWithOwner(form.getId());
            validateItemOwner(item, currentUser);
            requireManualItem(item);
            validateOwnWritableReport(currentUser, item.getWeeklyReportId());
            if (item.getStatus() != form.getStatus()) {
                item.setDisplayOrder(reportMapper.maxItemOrderByStatus(item.getWeeklyReportId(), form.getStatus()) + 1);
                item.setStatus(form.getStatus());
            }
        }

        item.setContent(form.getContent().trim());
        item.setDueDate(WeekUtils.enforceWeekday(form.getDueDate()));
        item.setCycle(trimToNull(form.getCycle()));
        if (form.getId() == null) {
            reportMapper.insertItem(item);
        } else {
            reportMapper.updateItem(item);
        }

        int nextDetailOrder = reportMapper.maxDetailOrder(item.getId());
        List<ReportItemBundleForm.Detail> formDetails = form.getDetails() == null ? List.of() : form.getDetails();
        for (ReportItemBundleForm.Detail formDetail : formDetails) {
            if (formDetail.getId() == null) {
                if (formDetail.getText() == null || formDetail.getText().isBlank()) {
                    continue;
                }
                ReportItemDetail detail = new ReportItemDetail();
                detail.setReportItemId(item.getId());
                detail.setText(formDetail.getText().trim());
                detail.setDueDate(WeekUtils.enforceWeekday(formDetail.getDueDate()));
                detail.setCycle(trimToNull(formDetail.getCycle()));
                detail.setDisplayOrder(++nextDetailOrder);
                reportMapper.insertDetail(detail);
                continue;
            }

            if (formDetail.getText() == null || formDetail.getText().isBlank()) {
                throw new BusinessException("세부 내용을 입력하세요.");
            }
            ReportItemDetail detail = reportMapper.findDetailWithOwner(formDetail.getId());
            if (detail == null || !item.getId().equals(detail.getReportItemId())) {
                throw new BusinessException("세부 항목을 찾을 수 없습니다.");
            }
            detail.setText(formDetail.getText().trim());
            detail.setDueDate(WeekUtils.enforceWeekday(formDetail.getDueDate()));
            detail.setCycle(trimToNull(formDetail.getCycle()));
            reportMapper.updateDetail(detail);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteItem(Long itemId, CustomUserDetails currentUser) {
        ReportItem item = reportMapper.findItemWithOwner(itemId);
        validateItemOwner(item, currentUser);
        requireManualItem(item);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        reportMapper.deleteItem(itemId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void moveMyItem(CustomUserDetails currentUser, Long itemId, int direction) {
        ReportItem item = reportMapper.findItemWithOwner(itemId);
        validateItemOwner(item, currentUser);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        requireProjectTeamsUnsubmitted(item.getWeeklyReportId(), itemWeekKey(item));
        swapOrder(item, reportMapper.findItemsByReportIdAndStatus(item.getWeeklyReportId(), item.getStatus()), direction);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeMyItemStatus(CustomUserDetails currentUser, Long itemId, ItemStatus status) {
        ReportItem item = reportMapper.findItemWithOwner(itemId);
        validateItemOwner(item, currentUser);
        requireManualItem(item);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        if (item.getStatus() == status) {
            return;
        }
        int order = reportMapper.maxItemOrderByStatus(item.getWeeklyReportId(), status) + 1;
        reportMapper.updateItemStatusAndOrder(itemId, status, order);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void moveTeamItem(CustomUserDetails currentUser, Long itemId, int direction) {
        ReportItem item = validateTeamLeaderItemWritable(currentUser, itemId);
        swapOrder(item, reportMapper.findSubmittedItemsByTeamAndStatus(currentUser.getTeamId(), itemWeekKey(item), item.getStatus()), direction);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void changeTeamItemStatus(CustomUserDetails currentUser, Long itemId, ItemStatus status) {
        ReportItem item = validateTeamLeaderItemWritable(currentUser, itemId);
        requireManualItem(item);
        if (item.getStatus() == status) {
            return;
        }
        int order = reportMapper.maxTeamItemOrderByStatus(currentUser.getTeamId(), itemWeekKey(item), status) + 1;
        reportMapper.updateItemStatusAndOrder(itemId, status, order);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void saveDetail(ReportDetailForm form, CustomUserDetails currentUser) {
        ReportItem owner = reportMapper.findItemWithOwner(form.itemId());
        validateItemOwner(owner, currentUser);
        requireManualItem(owner);
        validateOwnWritableReport(currentUser, owner.getWeeklyReportId());
        if (form.id() == null) {
            ReportItemDetail detail = new ReportItemDetail();
            detail.setReportItemId(form.itemId());
            detail.setText(form.text().trim());
            detail.setDueDate(WeekUtils.enforceWeekday(form.dueDate()));
            detail.setCycle(trimToNull(form.cycle()));
            detail.setDisplayOrder(reportMapper.maxDetailOrder(form.itemId()) + 1);
            reportMapper.insertDetail(detail);
            return;
        }
        ReportItemDetail detail = reportMapper.findDetailWithOwner(form.id());
        if (detail == null) {
            throw new BusinessException("세부 항목을 찾을 수 없습니다.");
        }
        ReportItem item = reportMapper.findItemWithOwner(detail.getReportItemId());
        validateItemOwner(item, currentUser);
        requireManualItem(item);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        detail.setText(form.text().trim());
        detail.setDueDate(WeekUtils.enforceWeekday(form.dueDate()));
        detail.setCycle(trimToNull(form.cycle()));
        reportMapper.updateDetail(detail);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteDetail(Long detailId, CustomUserDetails currentUser) {
        ReportItemDetail detail = reportMapper.findDetailWithOwner(detailId);
        if (detail == null) {
            throw new BusinessException("세부 항목을 찾을 수 없습니다.");
        }
        ReportItem item = reportMapper.findItemWithOwner(detail.getReportItemId());
        validateItemOwner(item, currentUser);
        requireManualItem(item);
        validateOwnWritableReport(currentUser, item.getWeeklyReportId());
        reportMapper.deleteDetail(detailId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void importFromWeek(CustomUserDetails currentUser, String sourceWeekKey, String targetWeekKey) {
        if (sourceWeekKey == null || sourceWeekKey.equals(targetWeekKey)) {
            throw new BusinessException("가져올 주차를 선택하세요.");
        }
        validateOwnWritableReport(currentUser, targetWeekKey);
        WeeklyReport source = reportMapper.findReport(currentUser.getUserId(), sourceWeekKey);
        if (source == null || reportMapper.countItemsByReportId(source.getId()) == 0) {
            throw new BusinessException("선택한 주차에 가져올 항목이 없습니다.");
        }
        List<ReportItem> sourceItems = attachDetails(reportMapper.findItemsByReportId(source.getId())).stream()
                .filter(item -> !item.isProjectLinked()).toList();
        if (sourceItems.isEmpty()) throw new BusinessException("선택한 주차에 직접 작성한 항목이 없습니다. 프로젝트 활동은 해당 주차에 자동 반영됩니다.");
        WeeklyReport target = getOrCreateReport(currentUser.getUserId(), targetWeekKey);
        reportMapper.deleteManualItemsByReportId(target.getId());
        reportMapper.markReportDraft(target.getId());
        for (ReportItem sourceItem : sourceItems) {
            ReportItem copied = new ReportItem();
            copied.setWeeklyReportId(target.getId());
            copied.setContent(sourceItem.getContent());
            copied.setDueDate(sourceItem.getDueDate());
            copied.setCycle(sourceItem.getCycle());
            copied.setStatus(sourceItem.getStatus());
            copied.setDisplayOrder(sourceItem.getDisplayOrder());
            reportMapper.insertItem(copied);
            for (ReportItemDetail sourceDetail : sourceItem.getDetails()) {
                ReportItemDetail copiedDetail = new ReportItemDetail();
                copiedDetail.setReportItemId(copied.getId());
                copiedDetail.setText(sourceDetail.getText());
                copiedDetail.setDueDate(sourceDetail.getDueDate());
                copiedDetail.setCycle(sourceDetail.getCycle());
                copiedDetail.setDisplayOrder(sourceDetail.getDisplayOrder());
                reportMapper.insertDetail(copiedDetail);
            }
        }
        reportMapper.insertAuditLog(targetWeekKey, currentUser.getUserId(), "IMPORT", sourceWeekKey + " 주차의 직접 작성 항목을 가져옴 · 프로젝트 연계 보존");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void submitMyReport(CustomUserDetails currentUser, String weekKey) {
        validateOwnWritableReport(currentUser, weekKey);
        WeeklyReport report = getOrCreateReport(currentUser.getUserId(), weekKey);
        requireProjectTeamsUnsubmitted(report.getId(), weekKey);
        syncProjectActivities(currentUser, weekKey);
        if (reportMapper.countItemsByReportId(report.getId()) == 0) {
            throw new BusinessException("작성한 항목이 없습니다.");
        }
        reportMapper.submitReport(report.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recallMyReport(CustomUserDetails currentUser, String weekKey) {
        lockWeek(weekKey);
        if (isWeekConfirmed(weekKey)) {
            throw new BusinessException("확정된 주간보고는 회수할 수 없습니다.");
        }
        if (isTeamSubmitted(currentUser.getTeamId(), weekKey)) {
            throw new BusinessException("팀이 최종 제출된 이후에는 팀장에게 문의하세요.");
        }
        WeeklyReport report = reportMapper.findReport(currentUser.getUserId(), weekKey);
        if (report != null) {
            requireProjectTeamsUnsubmitted(report.getId(), weekKey);
            reportMapper.recallReport(report.getId());
            syncProjectActivities(currentUser, weekKey);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void teamSubmit(CustomUserDetails currentUser, String weekKey) {
        requireTeamLeader(currentUser);
        lockWeek(weekKey);
        if (isWeekConfirmed(weekKey)) {
            throw new BusinessException("확정된 주간보고는 변경할 수 없습니다.");
        }
        reportMapper.upsertTeamStatus(currentUser.getTeamId(), weekKey, true);
        reportMapper.insertAuditLog(weekKey, currentUser.getUserId(), "TEAM_SUBMIT", "팀 보고 최종제출");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void teamRecall(CustomUserDetails currentUser, String weekKey) {
        requireTeamLeader(currentUser);
        lockWeek(weekKey);
        if (isWeekConfirmed(weekKey)) {
            throw new BusinessException("확정된 주간보고는 회수할 수 없습니다.");
        }
        reportMapper.upsertTeamStatus(currentUser.getTeamId(), weekKey, false);
        reportMapper.insertAuditLog(weekKey, currentUser.getUserId(), "TEAM_RECALL", "팀 보고 회수");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmWeek(CustomUserDetails currentUser, String weekKey) {
        if (!organizationService.canManageWeeklyReport(currentUser)) {
            throw new BusinessException("주간보고 관리자 권한이 없습니다.");
        }
        lockWeek(weekKey);
        reportMapper.upsertWeekConfirmation(weekKey, currentUser.getUserId());
        reportMapper.upsertSnapshot(weekKey, buildSnapshotHtml(weekKey), currentUser.getUserId());
        reportMapper.insertAuditLog(weekKey, currentUser.getUserId(), "CONFIRM", "주간보고 확정 및 스냅샷 저장");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelWeek(CustomUserDetails currentUser, String weekKey) {
        if (!organizationService.canManageWeeklyReport(currentUser)) {
            throw new BusinessException("주간보고 관리자 권한이 없습니다.");
        }
        lockWeek(weekKey);
        reportMapper.cancelWeekConfirmation(weekKey);
        reportMapper.insertAuditLog(weekKey, currentUser.getUserId(), "CONFIRM_CANCEL", "주간보고 확정 취소");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void logPrint(CustomUserDetails currentUser, String weekKey, String scope) {
        reportMapper.insertAuditLog(weekKey, currentUser.getUserId(), "PRINT", scope + " 출력/PDF 열람");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void moveAdminTeam(CustomUserDetails currentUser, String weekKey, Long teamId, int direction) {
        if (!organizationService.canManageWeeklyReport(currentUser)) {
            throw new BusinessException("주간보고 관리자 권한이 없습니다.");
        }
        lockWeek(weekKey);
        if (isWeekConfirmed(weekKey)) {
            throw new BusinessException("확정된 주간보고는 팀 순서를 변경할 수 없습니다.");
        }
        List<Team> teams = teamMapper.findAll();
        int index = -1;
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).getId().equals(teamId)) {
                index = i;
                break;
            }
        }
        int nextIndex = index + direction;
        if (index < 0 || nextIndex < 0 || nextIndex >= teams.size()) {
            return;
        }
        Team current = teams.remove(index);
        teams.add(nextIndex, current);
        for (int i = 0; i < teams.size(); i++) {
            teamMapper.updateDisplayOrder(teams.get(i).getId(), (i + 1) * 10);
        }
        reportMapper.insertAuditLog(weekKey, currentUser.getUserId(), "ADMIN_TEAM_ORDER", "관리자 취합 팀 순서 변경");
    }

    @Transactional(readOnly = true)
    public List<SearchResult> search(String query, String weekKey, CustomUserDetails currentUser) {
        // 슈퍼관리자/주간보고 관리자는 전체 검색
        if (organizationService.canManageWeeklyReport(currentUser)) {
            return reportMapper.search(query, weekKey, null, null);
        }
        // [P-6] 본부장/팀장 등 ledNodes 보유자는 산하 직원 + 본인까지 검색 가능
        java.util.Set<Long> scope = hrService.reportSearchScope(currentUser);
        if (scope.size() <= 1) {
            // 본인만
            return reportMapper.search(query, weekKey, null, currentUser.getUserId());
        }
        // 본인 + 산하 직원 → 전체 검색 후 scope으로 필터
        return reportMapper.search(query, weekKey, null, null).stream()
                .filter(r -> r.getUserId() != null && scope.contains(r.getUserId()))
                .toList();
    }

    private ReportItem validateTeamLeaderItemWritable(CustomUserDetails currentUser, Long itemId) {
        requireTeamLeader(currentUser);
        ReportItem item = reportMapper.findItemWithOwner(itemId);
        if (item == null || !currentUser.getTeamId().equals(item.getTeamId())) {
            throw new BusinessException("팀 항목을 찾을 수 없습니다.");
        }
        String weekKey = itemWeekKey(item);
        lockWeek(weekKey);
        if (isWeekConfirmed(weekKey)) {
            throw new BusinessException("확정된 주간보고는 수정할 수 없습니다.");
        }
        if (isTeamSubmitted(currentUser.getTeamId(), weekKey)) {
            throw new BusinessException("팀 보고가 최종 제출된 후에는 회수해야 수정할 수 있습니다.");
        }
        return item;
    }

    private String itemWeekKey(ReportItem item) {
        WeeklyReport report = reportMapper.findReportById(item.getWeeklyReportId());
        if (report == null) {
            throw new BusinessException("보고서를 찾을 수 없습니다.");
        }
        return report.getWeekKey();
    }

    private void swapOrder(ReportItem item, List<ReportItem> rows, int direction) {
        int index = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId().equals(item.getId())) {
                index = i;
                break;
            }
        }
        int nextIndex = index + (direction < 0 ? -1 : 1);
        if (index < 0 || nextIndex < 0 || nextIndex >= rows.size()) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            int targetOrder = i + 1;
            if (i == index) {
                targetOrder = nextIndex + 1;
            } else if (i == nextIndex) {
                targetOrder = index + 1;
            }
            reportMapper.updateItemOrder(rows.get(i).getId(), targetOrder);
        }
    }

    private List<ReportItem> attachDetails(List<ReportItem> items) {
        List<ReportItem> attached = new ArrayList<>(items);
        attached.forEach(item -> item.setDetails(reportMapper.findDetailsByItemId(item.getId())));
        return attached;
    }

    private Map<ItemStatus, List<ReportItem>> groupItems(List<ReportItem> items) {
        Map<ItemStatus, List<ReportItem>> grouped = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) {
            grouped.put(status, items.stream().filter(item -> item.getStatus() == status).toList());
        }
        return grouped;
    }

    private boolean isTeamSubmitted(Long teamId, String weekKey) {
        if (teamId == null) return false;
        TeamWeeklyStatus status = reportMapper.findTeamStatus(teamId, weekKey);
        return status != null && Boolean.TRUE.equals(status.getSubmitted());
    }

    private void validateOwnWritableReport(CustomUserDetails currentUser, String weekKey) {
        lockWeek(weekKey);
        if (isWeekConfirmed(weekKey)) {
            throw new BusinessException("확정된 주간보고는 수정할 수 없습니다.");
        }
        if (isTeamSubmitted(currentUser.getTeamId(), weekKey)) {
            throw new BusinessException("팀이 최종 제출된 이후에는 팀장에게 문의하세요.");
        }
        WeeklyReport report = reportMapper.findReport(currentUser.getUserId(), weekKey);
        if (report != null && Boolean.TRUE.equals(report.getSubmitted())) {
            throw new BusinessException("제출한 보고서는 회수 후 수정할 수 있습니다.");
        }
    }

    private void validateOwnWritableReport(CustomUserDetails currentUser, Long reportId) {
        WeeklyReport report = reportMapper.findReportById(reportId);
        if (report == null) {
            throw new BusinessException("보고서를 찾을 수 없습니다.");
        }
        validateOwnWritableReport(currentUser, report.getWeekKey());
    }

    private void validateItemOwner(ReportItem item, CustomUserDetails currentUser) {
        if (item == null) {
            throw new BusinessException("항목을 찾을 수 없습니다.");
        }
        if (!item.getUserId().equals(currentUser.getUserId())) {
            throw new BusinessException("본인 항목만 수정할 수 있습니다.");
        }
    }

    private void requireTeamLeader(CustomUserDetails currentUser) {
        if (currentUser == null || !currentUser.isTeamLeader()) {
            throw new BusinessException("팀장 권한이 필요합니다.");
        }
    }

    private void lockWeek(String weekKey) {
        if (!WeekUtils.isValidWeekKey(weekKey)
                || !weekKey.equals(WeekUtils.getWeekKey(WeekUtils.getWeekStart(weekKey)))) {
            throw new BusinessException("올바르지 않은 주차입니다.");
        }
        reportMapper.ensureWeekLock(weekKey);
        reportMapper.lockWeek(weekKey);
    }

    private void requireManualItem(ReportItem item) {
        if (item.isProjectLinked()) throw new BusinessException("프로젝트 연계 내용은 프로젝트에서 변경해 주세요. 보고용 보충 설명은 별도로 작성할 수 있습니다.");
    }

    private void requireProjectTeamsUnsubmitted(Long reportId, String weekKey) {
        if (reportMapper.countSubmittedProjectTeams(reportId, weekKey) > 0) {
            throw new BusinessException("활동 당시 소속 팀이 보고를 최종 제출했습니다. 해당 팀의 제출을 먼저 회수해야 개인 보고를 제출·회수하거나 순서를 바꿀 수 있습니다.");
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String buildSnapshotHtml(String weekKey) {
        StringBuilder html = new StringBuilder();
        html.append("<h1>오피스 시스템 주간보고</h1>");
        html.append("<p>").append(WeekUtils.getWeekLabel(weekKey)).append(" (").append(WeekUtils.getWeekPeriod(weekKey)).append(")</p>");
        adminItems(weekKey).forEach((team, grouped) -> {
            html.append("<h2>").append(escapeHtml(team.getName())).append("</h2>");
            for (ItemStatus status : ItemStatus.values()) {
                html.append("<h3>").append(status.getLabel()).append("</h3><ul>");
                for (ReportItem item : grouped.getOrDefault(status, List.of())) {
                    html.append("<li>").append(escapeHtml(item.getMemberName())).append(" - ").append(escapeHtml(item.getContent())).append("</li>");
                    if (item.isProjectLinked()) {
                        html.append("<p>프로젝트: ").append(escapeHtml(item.getSourceProjectName())).append("</p><pre>")
                                .append(escapeHtml(item.getProjectSummary())).append("</pre>");
                        if (item.getProjectNote() != null) html.append("<p>보충 설명: ").append(escapeHtml(item.getProjectNote())).append("</p>");
                    }
                }
                html.append("</ul>");
            }
        });
        return html.toString();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
