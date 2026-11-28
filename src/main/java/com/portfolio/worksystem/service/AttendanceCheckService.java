package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.model.OvertimeSession;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [OT] 출퇴근 버튼의 진입점 — 지금 누른 것이 <b>정규 근무</b>인지 <b>시간외 기록</b>인지 가른다.
 *
 * <p>버튼은 하나다. 휴무일이거나, 출근 없이 기준 퇴근시각을 넘겼거나, 퇴근 뒤 다시 나온 경우에는
 * 정규 근태(하루 1건)가 아니라 시간외 기록(overtime_sessions)으로 남긴다. 지각·조퇴 판정을 하지 않고,
 * 승인된 시간외 신청과 겹치는 만큼만 나중에 인정 시간이 된다.
 *
 * <p>웹·윈도우 앱·(앞으로) 외부 출입 단말이 모두 여기로 들어온다 — {@code source} 로 구분만 한다.
 */
@Service
@RequiredArgsConstructor
public class AttendanceCheckService {

    public static final String SOURCE_WEB = "WEB";
    public static final String SOURCE_DESKTOP = "DESKTOP";

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** 이보다 오래 열려 있는 시간외 기록은 퇴근을 잊은 것이다 — 버튼으로 닫지 않고 신청 화면에서 시각을 직접 적게 한다. */
    private static final int OPEN_SESSION_HOURS = 24;

    private final HrService hrService;
    private final OvertimeMapper overtimeMapper;
    private final PanelRefreshPublisher panelRefreshPublisher;
    private final OutsideWorkService outsideWorkService;
    private final OvertimeNoticeService overtimeNoticeService;

    /** 아직 퇴근을 찍지 않은 시간외 기록(없으면 null). */
    @Transactional(readOnly = true)
    public OvertimeSession openSession(Long userId) {
        return overtimeMapper.findOpenSession(userId, LocalDateTime.now(SERVICE_ZONE).minusHours(OPEN_SESSION_HOURS));
    }

    @Transactional(readOnly = true)
    public List<OvertimeSession> todaySessions(Long userId) {
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        return overtimeMapper.findSessions(userId, today, today);
    }

    /** 지금 출근을 누르면 시간외 기록이 되는 사유(정규 근무면 null). */
    @Transactional(readOnly = true)
    public String overtimeModeReason(Long userId) {
        return hrService.todayOvertimeModeReason(userId);
    }

    @Transactional
    public void checkIn(CustomUserDetails currentUser, String source) {
        Long userId = currentUser.getUserId();
        String blockReason = hrService.todayCheckBlockReason(userId);
        if (blockReason != null) {
            throw new BusinessException(blockReason);
        }
        if (hrService.todayOvertimeModeReason(userId) == null) {
            hrService.checkIn(currentUser, source);
            // [OW] 승인된 외근이 정해진 출근시각에 걸치면 출근 = 정해진 출근시각(지각 아님)
            outsideWorkService.reconcile(userId, LocalDate.now(SERVICE_ZONE));
            return;
        }
        if (openSession(userId) != null) {
            throw new BusinessException("이미 시간외 출근 처리되었습니다. 퇴근 체크를 먼저 하세요.");
        }
        LocalDateTime now = LocalDateTime.now(SERVICE_ZONE);
        OvertimeSession session = new OvertimeSession();
        session.setUserId(userId);
        session.setWorkDate(now.toLocalDate());
        session.setCheckInAt(now);
        session.setSource(source == null ? SOURCE_WEB : source);
        overtimeMapper.insertSession(session);
        panelRefreshPublisher.publishAllActive("attendance-check-in");
    }

    @Transactional
    public void checkOut(CustomUserDetails currentUser) {
        try {
            doCheckOut(currentUser);
        } finally {
            // 웹·윈도우 앱 어느 쪽으로 퇴근해도 방금 닫힌 기록이 미신청 시간외 안내에 바로 잡히게 한다
            overtimeNoticeService.evict(currentUser.getUserId());
        }
    }

    private void doCheckOut(CustomUserDetails currentUser) {
        OvertimeSession open = openSession(currentUser.getUserId());
        if (open == null) {
            hrService.checkOut(currentUser);
            // [OW] 승인된 외근이 정해진 퇴근시각에 걸치면 퇴근 = 정해진 퇴근시각(조퇴 아님)
            outsideWorkService.reconcile(currentUser.getUserId(), LocalDate.now(SERVICE_ZONE));
            return;
        }
        overtimeMapper.closeSession(open.getId(), LocalDateTime.now(SERVICE_ZONE));
        panelRefreshPublisher.publishAllActive("attendance-check-out");
    }

    /** 확인 창에 보일 판정 미리보기 — 시간외 기록이 될 때는 그 사실을 먼저 알린다. */
    @Transactional(readOnly = true)
    public HrService.CheckPreview preview(Long userId, String kind) {
        String now = LocalDateTime.now(SERVICE_ZONE).format(TIME_LABEL);
        if ("out".equals(kind)) {
            return openSession(userId) != null
                    ? new HrService.CheckPreview(true, "시간외 퇴근", now)
                    : hrService.checkPreview(userId, kind);
        }
        if (hrService.todayCheckBlockReason(userId) != null) {
            return hrService.checkPreview(userId, kind);
        }
        String reason = hrService.todayOvertimeModeReason(userId);
        if (reason == null) {
            return hrService.checkPreview(userId, kind);
        }
        if (openSession(userId) != null) {
            return new HrService.CheckPreview(false, "이미 시간외 출근 처리되었습니다.", null);
        }
        return new HrService.CheckPreview(true, "시간외 출근(" + reason + ")", now);
    }
}
