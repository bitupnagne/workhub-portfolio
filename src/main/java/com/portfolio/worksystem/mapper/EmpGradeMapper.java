package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.EmpGrade;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmpGradeMapper {
    List<EmpGrade> findAllWithUserCount();
    List<EmpGrade> findActiveOrdered();
    EmpGrade findById(Long id);
    String findGradeNameByUserId(Long userId);
}
