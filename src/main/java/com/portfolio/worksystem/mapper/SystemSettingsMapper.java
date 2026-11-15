package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.SystemSetting;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SystemSettingsMapper {
    List<SystemSetting> findAll();
    void upsert(@Param("key") String key, @Param("value") String value, @Param("updatedBy") Long updatedBy);
}
