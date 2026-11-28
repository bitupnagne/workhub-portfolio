package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.OvertimeMapper;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.OvertimeRequest;
import com.portfolio.worksystem.model.OvertimeSession;
import com.portfolio.worksystem.model.OvertimeSetting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [OT] 신청하지 않은 시간외 근무 — 출퇴근 기록은 있는데 신청서가 없는 구간을 찾아 신청을 유도한다.
 *
 * <p>사전 신청이 원칙이지만 급한 일로 그냥 나와 일한 경우가 있다. 그 근무는 <b>그 달 안에</b> 사후 신청해야
 * 인정되므로, 놓치기 전에 알려 주는 것이 이 서비스의 일이다. 그래서 <b>이번 달</b>만 본다 —
 * 지난달 것은 이미 신청할 수 없어 알려도 할 수 있는 일이 없다.
 *
 * <p>후보가 되는 것:
 * <ul>
 *   <li>퇴근까지 찍힌 시간외 기록(휴무일·퇴근시각 이후·재출근)</li>
 *   <li>정규 근무일에 기준 퇴근시각을 안내 기준(기본 30분) 이상 넘겨 퇴근한 구간</li>
 *   <li>정규 근무일에 기준 출근시각보다 안내 기준 이상 일찍 출근한 구간</li>
 * </ul>
 * 진행 중이거나 승인된 신청과 <b>조금이라도 겹치면</b> 이미 올린 것으로 보고 뺀다.
 */
@Service
@RequiredArgsConstructor
public class OvertimeNoticeService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final long COUNT_CACHE_MILLIS = 60_000L;

    private final OvertimeMapper overtimeMapper;
    private final HrMapper hrMapper;
    private final HrService hrService;

    /** 화면마다 건수를 다시 세지 않도록 잠깐 기억한다(사람별 1분). 신청·출퇴근 직후에는 {@link #evict} 로 비운다. */
    private final Map<Long, long[]> countCache = new ConcurrentHashMap<>();

    /** 신청 화면에 그대로 넘길 수 있는 미신청 구간. */
    public record Candidate(LocalDate workDate, LocalDateTime startAt, LocalDateTime endAt, String kindLabel) {
        public int getMinutes() {
            return OvertimeCalculator.minutesBetween(startAt, endAt);
        }

        public String getDurationText() {
            return OvertimeRequest.minutesText(getMinutes());
        }

        public String getStartTime() {
            return startAt.format(TIME);
        }

        public String getEndTime() {
            return endAt.format(TIME);
        }

        public String getTimeText() {
            boolean nextDay = !endAt.toLocalDate().equals(startAt.toLocalDate());
            return startAt.format(TIME) + " ~ " + (nextDay ? "익일 " : "") + endAt.format(TIME);
        }
    }

    /** 이번 달의 미신청 시간외 근무(근무일 순). */
    @Transactional(readOnly = true)
    public List<Candidate> unrequested(Long userId) {
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        YearMonth month = YearMonth.from(today);
        LocalDate from = month.atDay(1);
        OvertimeSetting setting = overtimeMapper.findSetting();
        int threshold = setting == null || setting.getNoticeThresholdMinutes() == null
                ? 30 : setting.getNoticeThresholdMinutes();

        List<Candidate> found = new ArrayList<>();
        for (OvertimeSession session : overtimeMapper.findSessions(userId, from, today)) {
            // 누르자마자 닫은 0분짜리 기록은 실수로 본다.
            if (session.getCheckOutAt() != null
                    && OvertimeCalculator.minutesBetween(session.getCheckInAt(), session.getCheckOutAt()) >= 1) {
                found.add(new Candidate(session.getWorkDate(), session.getCheckInAt(), session.getCheckOutAt(), "시간외 출퇴근"));
            }
        }
        for (AttendanceRecord record : hrMapper.findAttendanceRecords(userId, null, from, today)) {
            if (record.getCheckInAt() == null || record.getCheckOutAt() == null) {
                continue;
            }
            HrService.WorkSchedule schedule = hrService.workSchedule(userId, record.getWorkDate());
            LocalDateTime scheduledStart = LocalDateTime.of(record.getWorkDate(), schedule.startTime());
            LocalDateTime scheduledEnd = LocalDateTime.of(record.getWorkDate(), schedule.endTime());
            // 실제로 있었던 시간만 센다 — 기준 시각 밖에서 출근·퇴근이 모두 끝난 날도 있다.
            LocalDateTime lateFrom = record.getCheckInAt().isAfter(scheduledEnd) ? record.getCheckInAt() : scheduledEnd;
            if (OvertimeCalculator.minutesBetween(lateFrom, record.getCheckOutAt()) >= threshold) {
                found.add(new Candidate(record.getWorkDate(), lateFrom, record.getCheckOutAt(), "퇴근시각 이후"));
            }
            LocalDateTime earlyTo = record.getCheckOutAt().isBefore(scheduledStart) ? record.getCheckOutAt() : scheduledStart;
            if (OvertimeCalculator.minutesBetween(record.getCheckInAt(), earlyTo) >= threshold) {
                found.add(new Candidate(record.getWorkDate(), record.getCheckInAt(), earlyTo, "출근시각 이전"));
            }
        }
        if (found.isEmpty()) {
            return found;
        }
        // 자정을 넘긴 신청은 근무일이 전날이므로 하루 앞에서부터 가져온다.
        List<OvertimeRequest> requests = overtimeMapper.findActiveRequests(userId, from.minusDays(1), today);
        return found.stream()
                .filter(candidate -> requests.stream().noneMatch(request ->
                        request.getStartAt().isBefore(candidate.endAt()) && request.getEndAt().isAfter(candidate.startAt())))
                .sorted(Comparator.comparing(Candidate::startAt))
                .toList();
    }

    /** 미신청 건수 — 모든 화면의 안내에 쓰이므로 1분간 기억한다. */
    @Transactional(readOnly = true)
    public int unrequestedCount(Long userId) {
        long now = System.currentTimeMillis();
        long[] cached = countCache.get(userId);
        if (cached != null && now - cached[0] < COUNT_CACHE_MILLIS) {
            return (int) cached[1];
        }
        int count = unrequested(userId).size();
        countCache.put(userId, new long[] {now, count});
        return count;
    }

    public void evict(Long userId) {
        if (userId != null) {
            countCache.remove(userId);
        }
    }
}
