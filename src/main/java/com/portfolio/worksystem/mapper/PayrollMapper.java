package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.GradeHourlyWage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [PAY] 직급별 통상시급 이력(V92). */
@Mapper
public interface PayrollMapper {

    /** 전체 이력 — 직급, 적용 시작일 순. */
    List<GradeHourlyWage> findAllWages();

    GradeHourlyWage findWage(@Param("id") Long id);

    /** 같은 직급·같은 시작일이면 금액만 바꾼다. */
    void upsertWage(GradeHourlyWage wage);

    void deleteWage(@Param("id") Long id);

    void insertLog(@Param("gradeId") Long gradeId, @Param("gradeName") String gradeName,
                   @Param("action") String action, @Param("effectiveFrom") LocalDate effectiveFrom,
                   @Param("hourlyWage") BigDecimal hourlyWage, @Param("actorId") Long actorId,
                   @Param("actorName") String actorName);
}
