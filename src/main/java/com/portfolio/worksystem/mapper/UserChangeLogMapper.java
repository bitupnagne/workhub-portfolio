package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.UserChangeLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [B9] 인사정보 변경이력. */
@Mapper
public interface UserChangeLogMapper {
    void insert(UserChangeLog log);
    /** 최신순. limit 은 인사카드에서 보여줄 만큼만. */
    List<UserChangeLog> findByUser(@Param("userId") Long userId, @Param("limit") int limit);
    int countByUser(@Param("userId") Long userId);
}
