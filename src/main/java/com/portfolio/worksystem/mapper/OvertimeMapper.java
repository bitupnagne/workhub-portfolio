package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.OvertimeGroupRule;
import com.portfolio.worksystem.model.OvertimeRequest;
import com.portfolio.worksystem.model.OvertimeSetting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [OT] 시간외 근무 — 설정·조별 기준·신청서. */
@Mapper
public interface OvertimeMapper {

    OvertimeSetting findSetting();

    void updateSetting(OvertimeSetting setting);

    /** 저장된 조별 기준만 돌려준다 — 행이 없는 조는 서비스가 DEFAULT 로 채운다. */
    List<OvertimeGroupRule> findGroupRules();

    void upsertGroupRule(OvertimeGroupRule rule);

    /** 기본 근무와 같은 값이면 행을 지워 다시 기본 근무 기준을 따르게 한다. */
    void deleteGroupRule(@Param("groupCode") String groupCode);

    void insertRequest(OvertimeRequest request);

    OvertimeRequest findRequest(@Param("id") Long id);

    /** userId 는 null 이면 제한 없음, userIds 는 null 이면 제한 없음. from~to 는 근무일 기준. */
    List<OvertimeRequest> findRequests(@Param("userId") Long userId, @Param("userIds") java.util.Collection<Long> userIds,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 같은 사람의 진행 중·승인된 신청 중 시간이 겹치는 것. */
    OvertimeRequest findOverlapping(@Param("userId") Long userId, @Param("startAt") LocalDateTime startAt,
                                    @Param("endAt") LocalDateTime endAt, @Param("excludeId") Long excludeId);

    void updateStatus(@Param("id") Long id, @Param("status") String status,
                      @Param("decidedBy") Long decidedBy, @Param("rejectedReason") String rejectedReason);

    void updateCurrentStep(@Param("id") Long id, @Param("step") Integer step);

    /** 진행 중·승인된 신청(근무일 범위) — 미신청 판정과 주간 합계에 쓴다. */
    List<OvertimeRequest> findActiveRequests(@Param("userId") Long userId,
                                             @Param("from") LocalDate from, @Param("to") LocalDate to);

    /* ── 시간외 출퇴근 기록 ── */

    void insertSession(com.portfolio.worksystem.model.OvertimeSession session);

    /** 아직 퇴근을 찍지 않은 가장 최근 기록 — since 이후에 출근한 것만(오래 방치된 기록은 정정 대상). */
    com.portfolio.worksystem.model.OvertimeSession findOpenSession(@Param("userId") Long userId,
                                                                @Param("since") LocalDateTime since);

    void closeSession(@Param("id") Long id, @Param("checkOutAt") LocalDateTime checkOutAt);

    List<com.portfolio.worksystem.model.OvertimeSession> findSessions(@Param("userId") Long userId,
                                                                   @Param("from") LocalDate from, @Param("to") LocalDate to);
}
