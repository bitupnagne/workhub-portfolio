package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.LeaveAllowanceRate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [D4] 연도·등급별 연차수당 단가(V82). */
@Mapper
public interface LeaveAllowanceMapper {

    /** 해당 연도에 <b>설정된</b> 단가만 돌려준다 — 없는 등급은 행 자체가 없다(미설정). */
    List<LeaveAllowanceRate> findByYear(@Param("year") int year);

    /** 단가가 한 번이라도 설정된 연도들(내림차순) — 화면 연도 선택지에 쓴다. */
    List<Integer> findYearsWithRates();

    void upsert(LeaveAllowanceRate rate);

    /** 빈 값으로 저장하면 '미설정'으로 되돌린다 — 0원과 구분해야 하므로 행을 지운다. */
    void delete(@Param("year") int year, @Param("gradeId") Long gradeId);
}
