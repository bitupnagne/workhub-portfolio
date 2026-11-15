package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.ApprovalMapper;
import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.TripReportMapper;
import com.portfolio.worksystem.model.ApprovalDocumentSnapshot;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApprovalStep;
import com.portfolio.worksystem.model.AttendanceCorrection;
import com.portfolio.worksystem.model.BusinessTrip;
import com.portfolio.worksystem.model.FlexTimeRequest;
import com.portfolio.worksystem.model.LeaveRequest;
import com.portfolio.worksystem.model.LeaveStatus;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 결재문서의 <b>열람과 보관</b> — 신청서를 결재 도장이 찍힌 HTML 로 굳혀 두는 일.
 *
 * <p>휴가·출장·출장보고·시차출근·근태정정은 결재가 끝나면 <b>그때 모습 그대로</b> 남아야 한다.
 * 나중에 신청 내용이 수정되거나 결재자가 퇴사하거나 서명 이미지를 바꿔도, 이미 승인된 문서는
 * 달라지면 안 되기 때문이다. 그래서 승인 시점에 본문·결재이력·서명을 렌더링해 파일로 떨군다
 * (서명 이미지는 원본을 지워도 남도록 스냅샷 전용으로 복제한다 —
 * {@link AccountService#copySignatureForSnapshot}).
 *
 * <p>HrService 에서 떼어낸 것이다. 인사 업무 로직(휴가 일수 계산, 근태 판정, 결재 진행)과
 * 문서 <b>렌더링</b>은 바뀌는 이유가 다르다 — 여기 코드는 대부분 HTML 문자열이고,
 * 고칠 일이 생기는 것도 서식이 바뀔 때뿐이다.
 *
 * <p>열람 권한은 문서 종류마다 다르게 판정한다({@code *Document} 메서드들). 인사담당자거나,
 * 본인이거나, 결재선에 들어 있으면 볼 수 있다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalDocumentService {

    private static final DateTimeFormatter SNAPSHOT_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HrMapper hrMapper;
    private final TripReportMapper tripReportMapper;
    private final ApprovalMapper approvalMapper;
    private final ApprovalService approvalService;
    private final AccountService accountService;
    private final HrScopeService hrScopeService;
    private final com.portfolio.worksystem.mapper.LoaMapper loaMapper;
    private final PortfolioStorageBudget storageBudget;

    @Value("${app.upload.approval-document-dir:uploads/approval-documents}")
    private String approvalDocumentDir;

    private boolean canManageHr(CustomUserDetails currentUser) {
        return hrScopeService.canManageHr(currentUser);
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> leaveApprovalSteps(Long requestId) {
        return approvalService.steps(ApprovalDocumentType.LEAVE, requestId);
    }

    @Transactional(readOnly = true)
    public List<ApprovalStep> tripApprovalSteps(Long tripId) {
        return approvalService.steps(ApprovalDocumentType.TRIP, tripId);
    }

    @Transactional(readOnly = true)
    public LeaveRequest leaveDocument(CustomUserDetails user, Long id) {
        LeaveRequest request = getLeaveRequest(id);
        boolean allowed = canManageHr(user)
                || request.getUserId().equals(user.getUserId())
                || approvalService.canViewDocument(user, ApprovalDocumentType.LEAVE, id);
        if (!allowed) {
            throw new BusinessException("휴가 문서 조회 권한이 없습니다.");
        }
        return request;
    }

    @Transactional(readOnly = true)
    public BusinessTrip tripDocument(CustomUserDetails user, Long id) {
        BusinessTrip trip = getBusinessTrip(id);
        requireTripVisible(user, trip);
        return trip;
    }

    private void requireHrDocVisible(CustomUserDetails user, ApprovalDocumentType documentType, Long documentId,
                                     Long ownerUserId, Long ownerTeamId, String label) {
        boolean allowed = canManageHr(user)
                || (user != null && ownerUserId != null && ownerUserId.equals(user.getUserId()))
                || approvalService.canViewDocument(user, documentType, documentId);
        if (!allowed) {
            throw new BusinessException(label + " 문서 조회 권한이 없습니다.");
        }
    }

    @Transactional(readOnly = true)
    public com.portfolio.worksystem.model.BusinessTripReport tripReportDocument(CustomUserDetails user, Long id) {
        com.portfolio.worksystem.model.BusinessTripReport report = getTripReport(id);
        boolean allowed = canManageHr(user)
                || report.getUserId().equals(user.getUserId())
                || approvalService.canViewDocument(user, ApprovalDocumentType.TRIP_REPORT, id);
        if (!allowed) {
            throw new BusinessException("출장보고서 조회 권한이 없습니다.");
        }
        return report;
    }

    @Transactional(readOnly = true)
    public FlexTimeRequest flexTimeDocument(CustomUserDetails user, Long id) {
        FlexTimeRequest request = getFlexTimeRequest(id);
        requireHrDocVisible(user, ApprovalDocumentType.FLEX_TIME, id, request.getUserId(), request.getTeamId(), "시차출근");
        return request;
    }

    @Transactional(readOnly = true)
    public AttendanceCorrection attendanceCorrectionDocument(CustomUserDetails user, Long id) {
        AttendanceCorrection correction = getAttendanceCorrection(id);
        requireHrDocVisible(user, ApprovalDocumentType.ATTENDANCE_CORRECTION, id,
                correction.getUserId(), correction.getTeamId(), "근태 정정");
        return correction;
    }

    @Transactional
    public ApprovalDocumentSnapshot approvalDocumentSnapshot(CustomUserDetails currentUser,
                                                             ApprovalDocumentType documentType,
                                                             Long documentId) {
        requireSnapshotVisible(currentUser, documentType, documentId);
        ApprovalDocumentSnapshot snapshot = approvalMapper.findDocumentSnapshot(documentType, documentId);
        if (snapshot != null) {
            return snapshot;
        }
        return storeFinalDocumentSnapshot(documentType, documentId,
                currentUser != null ? currentUser.getUserId() : null);
    }

    @Transactional
    public void postApproveDocument(CustomUserDetails currentUser, ApprovalDocumentType documentType, Long documentId) {
        requireSnapshotVisible(currentUser, documentType, documentId);
        approvalService.postApproveStep(documentType, documentId, currentUser);
        ApprovalDocumentSnapshot refreshed = buildDocumentSnapshot(documentType, documentId, currentUser.getUserId());
        writeSnapshotFile(refreshed);
        ApprovalDocumentSnapshot existing = approvalMapper.findDocumentSnapshot(documentType, documentId);
        if (existing == null) {
            approvalMapper.insertDocumentSnapshot(refreshed);
            return;
        }
        refreshed.setId(existing.getId());
        approvalMapper.updateDocumentSnapshotHtml(refreshed);
        approvalMapper.updateDocumentSnapshotFile(refreshed);
    }

    private void requireSnapshotVisible(CustomUserDetails currentUser,
                                        ApprovalDocumentType documentType,
                                        Long documentId) {
        switch (documentType) {
            case LEAVE -> leaveDocument(currentUser, documentId);
            case TRIP -> tripDocument(currentUser, documentId);
            case TRIP_REPORT -> tripReportDocument(currentUser, documentId);
            case FLEX_TIME -> flexTimeDocument(currentUser, documentId);
            case ATTENDANCE_CORRECTION -> attendanceCorrectionDocument(currentUser, documentId);
            case LOA -> loaDocument(currentUser, documentId);
        }
    }

    public ApprovalDocumentSnapshot storeFinalDocumentSnapshot(ApprovalDocumentType documentType,
                                                               Long documentId,
                                                               Long generatedBy) {
        ApprovalDocumentSnapshot existing = approvalMapper.findDocumentSnapshot(documentType, documentId);
        if (existing != null) {
            return existing;
        }
        ApprovalDocumentSnapshot snapshot = buildDocumentSnapshot(documentType, documentId, generatedBy);
        writeSnapshotFile(snapshot);
        approvalMapper.insertDocumentSnapshot(snapshot);
        ApprovalDocumentSnapshot saved = approvalMapper.findDocumentSnapshot(documentType, documentId);
        return saved != null ? saved : snapshot;
    }

    @Transactional
    public StoredApprovalDocument loadApprovalDocumentSnapshotFile(CustomUserDetails currentUser,
                                                                   ApprovalDocumentType documentType,
                                                                   Long documentId) {
        requireSnapshotVisible(currentUser, documentType, documentId);
        ApprovalDocumentSnapshot snapshot = approvalMapper.findDocumentSnapshot(documentType, documentId);
        if (snapshot == null) {
            snapshot = approvalDocumentSnapshot(currentUser, documentType, documentId);
        }
        if (!StringUtils.hasText(snapshot.getStoredFilename())) {
            writeSnapshotFile(snapshot);
            if (snapshot.getId() != null) {
                approvalMapper.updateDocumentSnapshotFile(snapshot);
            }
        }
        Path root = approvalDocumentRoot();
        Path file = root.resolve(snapshot.getStoredFilename()).normalize();
        if (!file.startsWith(root) || !Files.exists(file)) {
            throw new BusinessException("보관 문서 파일을 찾을 수 없습니다.");
        }
        try {
            return new StoredApprovalDocument(new UrlResource(file.toUri()),
                    MediaType.parseMediaType(snapshot.getContentType()),
                    snapshot.getOriginalFilename());
        } catch (IOException e) {
            throw new BusinessException("보관 문서 파일을 읽을 수 없습니다.");
        }
    }

    private ApprovalDocumentSnapshot buildDocumentSnapshot(ApprovalDocumentType documentType,
                                                          Long documentId,
                                                          Long generatedBy) {
        return switch (documentType) {
            case LEAVE, LEAVE_CANCEL -> {
                LeaveRequest request = getLeaveRequest(documentId);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("구분", request.getType() != null ? request.getTypeLabel() : "-");
                details.put("기간", request.getStartDate() + " ~ " + request.getEndDate());
                details.put("일수", formatDays(request.getDays()) + "일");
                details.put("사유", blankToDash(request.getReason()));
                boolean isCancel = documentType == ApprovalDocumentType.LEAVE_CANCEL
                        || request.getStatus() == LeaveStatus.CANCEL_PENDING
                        || request.getStatus() == LeaveStatus.CANCEL_APPROVED;
                if (isCancel && StringUtils.hasText(request.getCancelReason())) {
                    details.put("취소 사유", request.getCancelReason());
                }
                String kind = isCancel ? "휴가 취소 신청서" : "휴가 신청서";
                String title = (request.getType() != null ? request.getTypeLabel() : "휴가")
                        + " " + formatDays(request.getDays()) + "일"
                        + (isCancel ? " (취소)" : "");
                yield snapshot(documentType, documentId, kind, title,
                        request.getUserId(), request.getMemberName(), request.getTeamName(),
                        request.getStatus() != null ? request.getStatus().getLabel() : "-",
                        request.getCreatedAt(), details, generatedBy);
            }
            case TRIP -> {
                BusinessTrip trip = getBusinessTrip(documentId);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("출장지", blankToDash(trip.getDestination()));
                details.put("기간", trip.getStartDate() + " ~ " + trip.getEndDate());
                details.put("목적", blankToDash(trip.getPurpose()));
                details.put("비용 메모", blankToDash(trip.getCostNote()));
                yield snapshot(documentType, documentId, "출장 신청서",
                        blankToDash(trip.getDestination()) + " 출장",
                        trip.getUserId(), trip.getMemberName(), trip.getTeamName(),
                        trip.getStatus() != null ? trip.getStatus().getLabel() : "-",
                        trip.getCreatedAt(), details, generatedBy);
            }
            case TRIP_REPORT -> {
                com.portfolio.worksystem.model.BusinessTripReport report = getTripReport(documentId);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("출장지", blankToDash(report.getDestination()));
                details.put("기간", report.getTripStartDate() + " ~ " + report.getTripEndDate());
                details.put("출장 내용", blankToDash(report.getContent()));
                details.put("경비", Boolean.TRUE.equals(report.getExpenseChanged()) ? "금액 변동사항 존재" : "신청 시와 동일");
                java.math.BigDecimal reported = tripReportMapper.findExpenses(documentId).stream()
                        .map(com.portfolio.worksystem.model.BusinessTripReportExpense::getAmount)
                        .filter(java.util.Objects::nonNull)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                details.put("보고 경비 합계", reported.stripTrailingZeros().toPlainString() + "원");
                yield snapshot(documentType, documentId, "출장 보고서",
                        blankToDash(report.getDestination()) + " 출장 보고",
                        report.getUserId(), report.getMemberName(), report.getTeamName(),
                        report.getStatus() != null ? report.getStatus().getLabel() : "-",
                        report.getCreatedAt(), details, generatedBy);
            }
            case FLEX_TIME -> {
                FlexTimeRequest request = getFlexTimeRequest(documentId);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("시차 그룹", request.getShiftGroupDisplay());
                details.put("기간", request.getStartDate() + " ~ " + request.getEndDate());
                details.put("사유", blankToDash(request.getReason()));
                yield snapshot(documentType, documentId, "시차출근 신청서",
                        request.getShiftGroup() != null ? request.getShiftGroupDisplay() + " 시차출근" : "시차출근",
                        request.getUserId(), request.getMemberName(), request.getTeamName(),
                        request.getStatus() != null ? request.getStatus().getLabel() : "-",
                        request.getCreatedAt(), details, generatedBy);
            }
            case LOA -> {
                com.portfolio.worksystem.model.LoaRequest loa = getLoa(documentId);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("구분", blankToDash(loa.getTypeLabel()));
                details.put("기간", loa.getPeriodText());
                details.put("연차 반영",
                        com.portfolio.worksystem.model.LoaAccrualTreatment.EXCLUDED.name().equals(loa.getAccrualTreatment())
                                ? "소정근로일 제외 (비례 삭감)" : "출근 간주 (연차 영향 없음)");
                details.put("사유", blankToDash(loa.getReason()));
                yield snapshot(documentType, documentId, "휴직 신청서",
                        blankToDash(loa.getTypeLabel()),
                        loa.getUserId(), loa.getMemberName(), loa.getTeamName(),
                        loa.getStatus() != null ? loa.getStatus().getLabel() : "-",
                        loa.getCreatedAt(), details, generatedBy);
            }
            case ATTENDANCE_CORRECTION -> {
                AttendanceCorrection correction = getAttendanceCorrection(documentId);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("근무일", String.valueOf(correction.getWorkDate()));
                details.put("정정 출근", correction.getRequestedCheckIn() != null ? String.valueOf(correction.getRequestedCheckIn()) : "-");
                details.put("정정 퇴근", correction.getRequestedCheckOut() != null ? String.valueOf(correction.getRequestedCheckOut()) : "-");
                details.put("사유", blankToDash(correction.getReason()));
                yield snapshot(documentType, documentId, "근태 정정 신청서", "근태 정정",
                        correction.getUserId(), correction.getMemberName(), correction.getTeamName(),
                        correction.getStatus() != null ? correction.getStatus().getLabel() : "-",
                        correction.getCreatedAt(), details, generatedBy);
            }
        };
    }

    private ApprovalDocumentSnapshot snapshot(ApprovalDocumentType documentType, Long documentId,
                                              String kind, String title, Long requesterUserId, String requesterName,
                                              String teamName, String statusLabel,
                                              LocalDateTime createdAt, Map<String, String> details,
                                              Long generatedBy) {
        List<ApprovalStep> steps = approvalService.steps(documentType, documentId);
        // 스냅샷 시점의 서명 이미지를 별도 파일로 "복제"해서 보관한다.
        // 사용자가 마이페이지에서 원본을 삭제/교체해도 스냅샷에는 당시 서명이 영구 보존된다.
        java.util.Map<Long, String> stepSigs = new java.util.HashMap<>();
        for (ApprovalStep step : steps) {
            Long signerId = step.getActedBy();
            if (signerId == null) continue;
            String orig = accountService.signatureFilenameForUser(signerId);
            String snap = accountService.copySignatureForSnapshot(orig);
            if (snap != null) stepSigs.put(signerId, snap);
        }
        String applicantOrig = requesterUserId != null
                ? accountService.signatureFilenameForUser(requesterUserId) : null;
        String applicantSig = accountService.copySignatureForSnapshot(applicantOrig);

        ApprovalDocumentSnapshot snapshot = new ApprovalDocumentSnapshot();
        snapshot.setDocumentType(documentType);
        snapshot.setDocumentId(documentId);
        snapshot.setDocumentKind(kind);
        snapshot.setDocumentTitle(title);
        snapshot.setRequesterName(requesterName);
        snapshot.setTeamName(teamName);
        snapshot.setStatusLabel(statusLabel);
        snapshot.setGeneratedBy(generatedBy);
        snapshot.setSnapshotHtml(renderSnapshotHtml(kind, title, requesterName, teamName,
                statusLabel, createdAt, details, steps, stepSigs, applicantSig));
        return snapshot;
    }

    private String renderSnapshotHtml(String kind, String title, String requesterName, String teamName,
                                      String statusLabel, LocalDateTime createdAt,
                                      Map<String, String> details, List<ApprovalStep> steps,
                                      java.util.Map<Long, String> stepSigs, String applicantSig) {
        StringBuilder html = new StringBuilder();
        html.append("<section class=\"snapshot-doc\">");
        html.append(renderSnapshotStamp(requesterName, createdAt, steps, stepSigs, applicantSig));
        html.append("<div class=\"snapshot-head\"><div><p class=\"snapshot-kind\">")
                .append(escape(kind))
                .append("</p><h1>")
                .append(escape(title))
                .append("</h1></div><span class=\"status-pill ")
                .append("반려".equals(statusLabel) ? "danger" : "ok")
                .append("\">")
                .append(escape(statusLabel))
                .append("</span></div>");
        html.append("<table class=\"doc-table\"><tbody>");
        html.append("<tr><th>신청자</th><td>").append(escape(blankToDash(requesterName)))
                .append("</td><th>소속</th><td>").append(escape(blankToDash(teamName))).append("</td></tr>");
        html.append("<tr><th>신청일</th><td colspan=\"3\">").append(escape(formatDateTime(createdAt))).append("</td></tr>");
        for (Map.Entry<String, String> entry : details.entrySet()) {
            html.append("<tr><th>").append(escape(entry.getKey())).append("</th><td colspan=\"3\">")
                    .append(escape(blankToDash(entry.getValue()))).append("</td></tr>");
        }
        html.append("</tbody></table>");
        html.append("<h2 class=\"snapshot-subtitle\">결재 이력</h2>");
        html.append("<table class=\"rt board-table snapshot-steps\"><thead><tr>")
                .append("<th>순서</th><th>결재자</th><th>부서</th><th>직책</th><th>대결</th><th>처리자</th><th>상태</th><th>처리일</th><th>반려 사유</th>")
                .append("</tr></thead><tbody>");
        for (ApprovalStep step : steps) {
            html.append("<tr><td>").append(step.getStepOrder() == null ? "-" : step.getStepOrder()).append("</td>")
                    .append("<td>").append(escape(blankToDash(step.getApproverName()))).append("</td>")
                    .append("<td>").append(escape(blankToDash(step.getApproverDept()))).append("</td>")
                    .append("<td>").append(escape(blankToDash(step.getApproverJobTitle()))).append("</td>")
                    .append("<td>").append(escape(blankToDash(step.getDelegateName()))).append("</td>")
                    .append("<td>").append(escape(blankToDash(step.getActedByName()))).append("</td>")
                    .append("<td>").append(escape(step.getStatus() != null ? step.getStatus().getLabel() : "-")).append("</td>")
                    .append("<td>").append(escape(formatDateTime(step.getActedAt()))).append("</td>")
                    .append("<td>").append(escape(blankToDash(step.getRejectedReason()))).append("</td></tr>");
        }
        if (steps.isEmpty()) {
            html.append("<tr><td colspan=\"9\">결재 이력이 없습니다.</td></tr>");
        }
        html.append("</tbody></table>");
        html.append("</section>");
        return html.toString();
    }

    private void writeSnapshotFile(ApprovalDocumentSnapshot snapshot) {
        Path root = approvalDocumentRoot();
        String filename = snapshot.getDocumentType().name().toLowerCase(Locale.ROOT)
                + "-" + snapshot.getDocumentId() + "-" + UUID.randomUUID() + ".html";
        String original = snapshot.getDocumentKind() + "-" + snapshot.getDocumentId() + "-보관문서.html";
        String html = buildSnapshotDocumentHtml(snapshot);
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("보관 문서 저장 경로가 올바르지 않습니다.");
        }
        try {
            Files.createDirectories(root);
            storageBudget.write(target, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "보관문서");
            snapshot.setStoredFilename(filename);
            snapshot.setOriginalFilename(original);
            snapshot.setContentType("text/html;charset=UTF-8");
            snapshot.setFileSize(Files.size(target));
        } catch (IOException e) {
            throw new BusinessException("보관 문서 파일 저장에 실패했습니다.");
        }
    }

    /** 보관문서 HTML(스타일 래퍼 + 본문) 생성. 본문(snapshotHtml)은 그대로 두고 래퍼만 입힌다. */
    private String buildSnapshotDocumentHtml(ApprovalDocumentSnapshot snapshot) {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                  <style>
                    body{margin:0;padding:24px;background:#fff;color:#111;font-family:Arial,'Malgun Gothic',sans-serif}
                    table{border-collapse:collapse}.doc-table,.rt{width:100%%}.doc-table th,.doc-table td,.rt th,.rt td{border:1px solid #cbd5e1;padding:8px 10px;font-size:12px;text-align:left}.doc-table th{background:#f8fafc;width:110px}
                    .snapshot-stamp-top{display:flex;justify-content:flex-end;margin:0 0 16px;overflow-x:auto}.approval-stamp{width:auto;table-layout:fixed;border-collapse:collapse}.approval-stamp th,.approval-stamp td{border:1px solid #333;text-align:center}.stamp-side{width:24px;background:#f1f3f5;color:#222;font-weight:900}.stamp-side span{display:block;line-height:1.5;letter-spacing:1px}.stamp-title{width:86px;height:34px;padding:6px;background:#f8fafc;font-size:11px;font-weight:800;color:#333}.stamp-sign{width:86px;height:78px;min-height:78px;padding:6px 4px;font-size:11px;font-weight:800;vertical-align:middle}.stamp-sign img{max-height:52px;max-width:74px;object-fit:contain;vertical-align:middle}.stamp-sign.is-ok{color:#0a7d6b}.stamp-sign.is-no{color:#b91c1c}.stamp-sign.is-wait{color:#64748b;font-weight:600}.stamp-typed{display:flex;flex-direction:column;align-items:center;justify-content:center;line-height:1.15;gap:1px}.stamp-typed b{font-size:10px;font-weight:900;white-space:nowrap}.stamp-typed small{font-size:7px;color:#555;white-space:nowrap;line-height:1.1}.stamp-typed img{max-height:30px;max-width:72px;object-fit:contain}.stamp-delegate-mark,.stamp-post-mark{display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;border:1px solid #333;border-radius:50%%;color:#b91c1c;font-style:normal;font-weight:900}.stamp-post-mark{color:#1f4e79}.stamp-drafter{background:#eef4fb;border-right:2px solid #333}.stamp-title.stamp-drafter{color:#1f4e79}
                    .snapshot-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:16px;padding-bottom:12px;border-bottom:2px solid #111}.snapshot-kind{margin:0 0 4px;color:#64748b;font-size:12px;font-weight:900}.snapshot-head h1{margin:0;font-size:22px;line-height:1.25}.status-pill{display:inline-flex;align-items:center;height:28px;padding:0 10px;border-radius:999px;background:#d1fae5;color:#065f46;font-size:12px;font-weight:900}.snapshot-subtitle{margin:18px 0 8px;font-size:14px;font-weight:900}.snapshot-steps th,.snapshot-steps td{white-space:nowrap}.rt thead th{background:#f9fafb}
                    .status-pill.danger{background:#fee2e2;color:#991b1b}
                  </style>
                </head>
                <body>%s</body>
                </html>
                """.formatted(escape(snapshot.getDocumentKind()), snapshot.getSnapshotHtml());
    }

    /**
     * 기존 보관문서 파일을 일괄 재생성한다(본문 유지, 스타일 래퍼만 최신화).
     * teamName 이 null/빈값이면 전체 팀 대상.
     *
     * <p>여기에는 롤백 정리를 붙이지 않는다 — 새 파일을 만드는 게 아니라 <b>이미 있는
     * 보관문서를 제자리에서 덮어쓰는</b> 것이라, 롤백됐다고 지우면 원본 결재문서가 사라진다.
     * 롤백 시 남는 어긋남은 file_size 컬럼이 되돌아가는 것뿐인데, 본문은 같은
     * snapshot_html 에서 다시 만들어지므로 다음 재생성에서 맞춰진다.
     *
     * @return 재생성된 파일 수
     */
    @Transactional
    public int regenerateSnapshotFiles(String teamName) {
        String team = (teamName == null || teamName.isBlank()) ? null : teamName.trim();
        List<ApprovalDocumentSnapshot> snapshots = approvalMapper.findSnapshotsByTeam(team);
        Path root = approvalDocumentRoot();
        int regenerated = 0;
        for (ApprovalDocumentSnapshot snapshot : snapshots) {
            if (snapshot.getSnapshotHtml() == null
                    || snapshot.getStoredFilename() == null || snapshot.getStoredFilename().isBlank()) {
                continue;
            }
            try {
                Path target = root.resolve(snapshot.getStoredFilename()).normalize();
                if (!target.startsWith(root)) {
                    continue;
                }
                storageBudget.write(target, buildSnapshotDocumentHtml(snapshot)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                snapshot.setFileSize(Files.size(target));
                approvalMapper.updateDocumentSnapshotFile(snapshot);
                regenerated++;
            } catch (IOException e) {
                log.warn("보관문서 재생성 실패 id={} file={}: {}",
                        snapshot.getId(), snapshot.getStoredFilename(), e.getMessage());
            }
        }
        log.info("보관문서 재생성 완료: team={}, {}건", team == null ? "(전체)" : team, regenerated);
        return regenerated;
    }

    private String renderSnapshotStamp(String requesterName, LocalDateTime createdAt, List<ApprovalStep> steps,
                                       java.util.Map<Long, String> stepSigs, String applicantSig) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        if (stepSigs == null) stepSigs = java.util.Collections.emptyMap();
        boolean hasPostApproval = steps.stream().anyMatch(step -> step.getPostApprovedAt() != null);
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"snapshot-stamp-top\"><table class=\"approval-stamp snapshot-stamp\"><tbody>");
        html.append("<tr><th class=\"stamp-side\" rowspan=\"").append(hasPostApproval ? 3 : 2).append("\"><span>결</span><span>재</span></th>");
        html.append("<td class=\"stamp-title stamp-drafter\">기안자</td>");
        for (ApprovalStep step : steps) {
            html.append("<td class=\"stamp-title\">").append(escape(stampTitle(step))).append("</td>");
        }
        html.append("</tr><tr>");
        html.append("<td class=\"stamp-sign stamp-drafter is-ok\"><span class=\"stamp-typed\"><b>")
                .append(escape(blankToDash(requesterName)))
                .append("</b>");
        if (applicantSig != null && !applicantSig.isBlank()) {
            String applicantSigSrc = accountService.signatureDataUriForSnapshot(applicantSig);
            if (applicantSigSrc == null) {
                applicantSigSrc = "/signature-images/" + applicantSig;
            }
            html.append("<img src=\"").append(escape(applicantSigSrc)).append("\" alt=\"기안자 서명\"/>");
        }
        html.append("<small>")
                .append(escape(formatDateTime(createdAt)))
                .append("</small></span></td>");
        for (ApprovalStep step : steps) {
            String status = step.getStatus() != null ? step.getStatus().name() : "PENDING";
            String statusClass = "APPROVED".equals(status) ? " is-ok" : ("REJECTED".equals(status) ? " is-no" : " is-wait");
            String actor = StringUtils.hasText(step.getActedByName())
                    ? step.getActedByName()
                    : (StringUtils.hasText(step.getDelegateName())
                    ? step.getApproverName() + " / 대리 " + step.getDelegateName()
                    : step.getApproverName());
            html.append("<td class=\"stamp-sign").append(statusClass).append("\"><span class=\"stamp-typed\"><b>")
                    .append(escape(blankToDash(actor)))
                    .append("</b>");
            String sigFn = step.getActedBy() != null ? stepSigs.get(step.getActedBy()) : null;
            if (sigFn != null && !sigFn.isBlank()) {
                String sigSrc = accountService.signatureDataUriForSnapshot(sigFn);
                if (sigSrc == null) {
                    sigSrc = "/signature-images/" + sigFn;
                }
                html.append("<img src=\"").append(escape(sigSrc)).append("\" alt=\"서명\"/>");
            }
            if (step.getActedBy() != null && step.getDelegateUserId() != null
                    && step.getActedBy().equals(step.getDelegateUserId())
                    && !step.getActedBy().equals(step.getApproverUserId())) {
                html.append("<em class=\"stamp-delegate-mark\">代</em>");
            }
            html.append("<small>")
                    .append(escape(step.getActedAt() != null ? formatDateTime(step.getActedAt())
                            : ("REJECTED".equals(status) ? "반려" : "대기")))
                    .append("</small></span></td>");
        }
        if (hasPostApproval) {
            html.append("</tr><tr>");
            html.append("<td class=\"stamp-sign stamp-drafter is-wait\"><span class=\"stamp-typed\"><b>후결</b></span></td>");
            for (ApprovalStep step : steps) {
                html.append("<td class=\"stamp-sign")
                        .append(step.getPostApprovedAt() != null ? " is-ok" : " is-wait")
                        .append("\"><span class=\"stamp-typed\">");
                if (step.getPostApprovedAt() != null) {
                    html.append("<em class=\"stamp-post-mark\">後</em><b>")
                            .append(escape(blankToDash(step.getPostApprovedByName())))
                            .append("</b><small>")
                            .append(escape(formatDateTime(step.getPostApprovedAt())))
                            .append("</small>");
                } else {
                    html.append("<b>-</b>");
                }
                html.append("</span></td>");
            }
        }
        html.append("</tr></tbody></table></div>");
        return html.toString();
    }

    private String stampTitle(ApprovalStep step) {
        if ("대표이사".equals(step.getApproverJobTitle()) || "대표이사".equals(step.getApproverDept())) {
            return "대표이사";
        }
        if (StringUtils.hasText(step.getApproverJobTitle())
                && step.getApproverJobTitle().endsWith("장")
                && StringUtils.hasText(step.getApproverDept())) {
            return step.getApproverDept() + "장";
        }
        return step.getApproverType() != null ? step.getApproverType().getLabel() : "결재";
    }

    /* ── 문서 조회 ────────────────────────────────────── */

    private LeaveRequest getLeaveRequest(Long requestId) {
        LeaveRequest request = hrMapper.findLeaveRequest(requestId);
        if (request == null) {
            throw new BusinessException("연차 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    private BusinessTrip getBusinessTrip(Long tripId) {
        BusinessTrip trip = hrMapper.findBusinessTrip(tripId);
        if (trip == null) {
            throw new BusinessException("출장 신청을 찾을 수 없습니다.");
        }
        return trip;
    }

    private FlexTimeRequest getFlexTimeRequest(Long requestId) {
        FlexTimeRequest request = hrMapper.findFlexTimeRequest(requestId);
        if (request == null) {
            throw new BusinessException("시차출근 신청을 찾을 수 없습니다.");
        }
        return request;
    }

    private AttendanceCorrection getAttendanceCorrection(Long correctionId) {
        AttendanceCorrection correction = hrMapper.findAttendanceCorrection(correctionId);
        if (correction == null) {
            throw new BusinessException("근태 정정 신청을 찾을 수 없습니다.");
        }
        return correction;
    }

    @Transactional(readOnly = true)
    public com.portfolio.worksystem.model.LoaRequest loaDocument(CustomUserDetails user, Long id) {
        com.portfolio.worksystem.model.LoaRequest loa = getLoa(id);
        requireHrDocVisible(user, ApprovalDocumentType.LOA, id, loa.getUserId(), loa.getTeamId(), "휴직");
        return loa;
    }

    private com.portfolio.worksystem.model.LoaRequest getLoa(Long id) {
        com.portfolio.worksystem.model.LoaRequest loa = loaMapper.findRequest(id);
        if (loa == null) {
            throw new BusinessException("휴직 신청을 찾을 수 없습니다.");
        }
        return loa;
    }

    private com.portfolio.worksystem.model.BusinessTripReport getTripReport(Long id) {
        com.portfolio.worksystem.model.BusinessTripReport report = tripReportMapper.findById(id);
        if (report == null) {
            throw new BusinessException("출장보고서를 찾을 수 없습니다.");
        }
        return report;
    }

    private void requireTripVisible(CustomUserDetails currentUser, BusinessTrip trip) {
        if (trip == null) {
            throw new BusinessException("출장 신청을 찾을 수 없습니다.");
        }
        if (canManageHr(currentUser) || trip.getUserId().equals(currentUser.getUserId())) {
            return;
        }
        if (approvalService.canViewDocument(currentUser, ApprovalDocumentType.TRIP, trip.getId())) {
            return;
        }
        ApprovalStep pendingStep = approvalService.pendingStep(ApprovalDocumentType.TRIP, trip.getId());
        if (pendingStep != null
                && (currentUser.getUserId().equals(pendingStep.getApproverUserId())
                || currentUser.getUserId().equals(pendingStep.getDelegateUserId()))) {
            return;
        }
        throw new BusinessException("출장 신청 조회 권한이 없습니다.");
    }

    /* ── 서식 도우미 ──────────────────────────────────── */

    private Path approvalDocumentRoot() {
        return Path.of(approvalDocumentDir).toAbsolutePath().normalize();
    }

    private String blankToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(SNAPSHOT_DATE_TIME);
    }

    private String formatDays(BigDecimal value) {
        BigDecimal days = value == null ? BigDecimal.ZERO : value;
        return days.stripTrailingZeros().toPlainString();
    }

    /** 보관문서는 신청 내용을 그대로 HTML 에 박아 넣으므로 반드시 이스케이프한다. */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public record StoredApprovalDocument(Resource resource, MediaType mediaType, String originalFilename) {
    }
}
