package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.DeploySsoLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeploySsoMapper {
    DeploySsoLink findActiveByOfficeUserId(Long officeUserId);
    void upsertLink(@Param("officeUserId") Long officeUserId,
                    @Param("officeUsername") String officeUsername,
                    @Param("deployUserId") Long deployUserId,
                    @Param("deployLoginId") String deployLoginId,
                    @Param("deployName") String deployName);
    void deactivate(Long officeUserId);
}
