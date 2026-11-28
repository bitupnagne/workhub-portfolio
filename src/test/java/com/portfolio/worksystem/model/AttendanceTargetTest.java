package com.portfolio.worksystem.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 입사(계정 생성) 전·퇴사 후 날짜가 결근으로 쌓이지 않도록 하는 판정 대상 기간. */
class AttendanceTargetTest {

    @Test
    void hireDateBoundsTheStart() {
        UserAccount user = new UserAccount();
        user.setHireDate(LocalDate.of(2026, 11, 10));
        user.setCreatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));

        assertThat(user.isAttendanceTarget(LocalDate.of(2026, 11, 9))).isFalse();
        assertThat(user.isAttendanceTarget(LocalDate.of(2026, 11, 10))).isTrue();
    }

    @Test
    void accountCreationDateIsTheFallbackWhenHireDateIsMissing() {
        UserAccount user = new UserAccount();
        user.setCreatedAt(LocalDateTime.of(2026, 11, 23, 20, 46));

        assertThat(user.getAttendanceStartDate()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(user.isAttendanceTarget(LocalDate.of(2026, 11, 20))).isFalse();
        assertThat(user.isAttendanceTarget(LocalDate.of(2026, 11, 23))).isTrue();
    }

    @Test
    void daysAfterResignationAreNotTargets() {
        UserAccount user = new UserAccount();
        user.setHireDate(LocalDate.of(2025, 3, 1));
        user.setResignedOn(LocalDate.of(2026, 11, 13));

        assertThat(user.isAttendanceTarget(LocalDate.of(2026, 11, 13))).isTrue();
        assertThat(user.isAttendanceTarget(LocalDate.of(2026, 11, 16))).isFalse();
    }

    @Test
    void unknownStartKeepsEveryDayATarget() {
        UserAccount user = new UserAccount();

        assertThat(user.isAttendanceTarget(LocalDate.of(2020, 1, 1))).isTrue();
        assertThat(user.isAttendanceTarget(null)).isFalse();
    }

    @Test
    void futureScheduledDayReadsAsPlannedNotMissing() {
        AttendanceDailyStatus status = new AttendanceDailyStatus();
        status.setStatus(AttendanceStatus.SCHEDULED);
        assertThat(status.getStatusLabel()).isEqualTo("미출근");

        status.setFuture(true);
        assertThat(status.getStatusLabel()).isEqualTo("예정");

        status.setStatus(AttendanceStatus.HOLIDAY);
        assertThat(status.getStatusLabel()).isEqualTo("휴무");
    }
}
