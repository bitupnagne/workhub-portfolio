package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.OutsideWorkRequest;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [OW] 외근 신청(V93). */
@Mapper
public interface OutsideWorkMapper {

    void insert(OutsideWorkRequest request);

    OutsideWorkRequest findById(@Param("id") Long id);

    List<OutsideWorkRequest> findRequests(@Param("userId") Long userId, @Param("userIds") Collection<Long> userIds,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 같은 날 진행 중이거나 승인된 외근(없으면 null). */
    OutsideWorkRequest findActiveOnDate(@Param("userId") Long userId, @Param("workDate") LocalDate workDate);

    /** 그날 최종 승인된 외근(없으면 null). */
    OutsideWorkRequest findApprovedOnDate(@Param("userId") Long userId, @Param("workDate") LocalDate workDate);

    void updateStatus(@Param("id") Long id, @Param("status") String status, @Param("decidedBy") Long decidedBy,
                      @Param("rejectedReason") String rejectedReason);

    void updateCurrentStep(@Param("id") Long id, @Param("step") int step);

    void markAttendanceApplied(@Param("id") Long id);
}
