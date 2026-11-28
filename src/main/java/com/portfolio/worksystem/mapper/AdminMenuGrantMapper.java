package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.AdminMenuGrant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [PERM] 관리자 메뉴 권한(V91). */
@Mapper
public interface AdminMenuGrantMapper {

    List<AdminMenuGrant> findAll();

    AdminMenuGrant findById(@Param("id") Long id);

    /** 이 사람이 가진 메뉴 — 본인 지정 + 지금 조직장인 조직에 준 것. */
    List<String> findMenuKeysForUser(@Param("userId") Long userId);

    int insert(AdminMenuGrant grant);

    int delete(@Param("id") Long id);

    void insertLog(@Param("menuKey") String menuKey, @Param("action") String action,
                   @Param("granteeType") String granteeType, @Param("userId") Long userId,
                   @Param("teamId") Long teamId, @Param("granteeLabel") String granteeLabel,
                   @Param("actorId") Long actorId, @Param("actorName") String actorName);
}
