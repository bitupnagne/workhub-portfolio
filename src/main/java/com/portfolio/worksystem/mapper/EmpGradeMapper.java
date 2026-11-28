package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.EmpGrade;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EmpGradeMapper {
    List<EmpGrade> findAllWithUserCount();
    List<EmpGrade> findActiveOrdered();
    EmpGrade findById(Long id);
    /** 이름으로 하나 — 최상위를 우선한다(bulkAssignByOrg 처럼 '3급' 같은 급 이름을 찾을 때). */
    EmpGrade findByName(String name);

    /** [D5] 같은 부모 아래 같은 이름이 있는지 — 이름은 형제 사이에서만 유일하다. */
    EmpGrade findSiblingByName(@Param("parentId") Long parentId, @Param("name") String name);

    Integer maxDisplayOrderOfSiblings(@Param("parentId") Long parentId);

    /** [D5] 여러 노드에 걸린 재직 직원 수 — 트리 삭제 가드는 자손까지 봐야 한다. */
    int countUsersByGradeIds(@Param("gradeIds") List<Long> gradeIds);

    void insert(EmpGrade grade);
    void update(EmpGrade grade);
    void delete(Long id);

    /** [GRADE] 값·단위로 다시 만든 보이는 이름. */
    void updateName(@org.apache.ibatis.annotations.Param("id") Long id, @org.apache.ibatis.annotations.Param("name") String name);

    void updateDisplayOrder(@org.apache.ibatis.annotations.Param("id") Long id,
                            @org.apache.ibatis.annotations.Param("displayOrder") int displayOrder);

    /** [GRADE] 단계별 기본 단위(depth → unit_label). */
    java.util.List<java.util.Map<String, Object>> findDepthUnits();

    void upsertDepthUnit(@org.apache.ibatis.annotations.Param("depth") int depth,
                         @org.apache.ibatis.annotations.Param("unitLabel") String unitLabel,
                         @org.apache.ibatis.annotations.Param("updatedBy") Long updatedBy);

    void deleteDepthUnit(@org.apache.ibatis.annotations.Param("depth") int depth);
    int countUsersByGrade(Long gradeId);
    String findGradeNameByUserId(Long userId);
    /** 조직도상 팀장/본부장(직책자) user id 목록. */
    List<Long> findLeaderUserIds();
    void updateUserGrade(@Param("userId") Long userId, @Param("gradeId") Long gradeId);
}
