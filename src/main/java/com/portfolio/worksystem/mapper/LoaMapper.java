package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.LoaRequest;
import com.portfolio.worksystem.model.LoaType;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [B7] 휴직 — 종류 정의와 신청서. */
@Mapper
public interface LoaMapper {

    List<LoaType> findTypes();
    LoaType findType(@Param("code") String code);
    void updateType(LoaType type);

    void insertRequest(LoaRequest request);
    LoaRequest findRequest(@Param("id") Long id);
    /** userId/teamId 는 null 이면 제한 없음. year 는 시작일 기준. */
    List<LoaRequest> findRequests(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                  @Param("year") Integer year);
    void updateStatus(@Param("id") Long id, @Param("status") String status,
                      @Param("decidedBy") Long decidedBy, @Param("rejectedReason") String rejectedReason);
    void updateCurrentStep(@Param("id") Long id, @Param("step") Integer step);

    /** 이미 진행 중이거나 승인된 휴직과 기간이 겹치는지. */
    LoaRequest findOverlapping(@Param("userId") Long userId, @Param("startDate") LocalDate startDate,
                               @Param("endDate") LocalDate endDate, @Param("excludeId") Long excludeId);

    /** 승인 시 users 에 현재 휴직을 반영. 복직(해제)이면 전부 null 로 넣는다. */
    void applyToUser(@Param("userId") Long userId, @Param("type") String type,
                     @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    int closeApprovedPeriod(@Param("userId") Long userId, @Param("type") String type,
                            @Param("startDate") LocalDate startDate, @Param("lastLeaveDate") LocalDate lastLeaveDate,
                            @Param("decidedBy") Long decidedBy);

    /**
     * 연차 출근율 계산용 — 해당 연도와 겹치는 <b>승인된</b> 휴직 기간.
     * users 의 현재 휴직도 신청서 없이 인사담당자가 직접 넣을 수 있어 함께 본다.
     */
    List<LoaRequest> findApprovedPeriodsInYear(@Param("year") int year);
}
