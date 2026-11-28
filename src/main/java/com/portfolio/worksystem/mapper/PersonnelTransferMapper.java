package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.PersonnelTransfer;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PersonnelTransferMapper {
    void insert(PersonnelTransfer t);
    PersonnelTransfer findById(@Param("id") Long id);
    List<PersonnelTransfer> findByYear(@Param("year") Integer year);
    /** [B5] 인사카드 발령 이력 — 사용자 전체 이력(최신순). */
    List<PersonnelTransfer> findByUser(@Param("userId") Long userId);
    /** 한 사용자의 특정 연도 '반영된' 인사이동(발령일 오름차순) — 집계 일수 비례용. */
    List<PersonnelTransfer> findAppliedByUserYear(@Param("userId") Long userId, @Param("year") Integer year);
    /** 발령일 도래(<= 오늘) + 미반영 — 스케줄러가 반영할 대상. */
    List<PersonnelTransfer> findDuePending(@Param("today") LocalDate today);
    void markApplied(@Param("id") Long id);
    void updateEffectiveDate(@Param("id") Long id, @Param("effectiveDate") LocalDate effectiveDate,
                             @Param("transferYear") Integer transferYear);
    void delete(@Param("id") Long id);
}
