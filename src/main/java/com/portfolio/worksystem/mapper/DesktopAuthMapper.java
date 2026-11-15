package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.DesktopDeviceToken;
import com.portfolio.worksystem.model.DesktopSsoToken;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DesktopAuthMapper {
    void insertDeviceToken(DesktopDeviceToken token);
    DesktopDeviceToken findActiveDeviceToken(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
    DesktopDeviceToken findLatestActiveDeviceTokenByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
    void markDeviceTokenUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
    void revokeDeviceToken(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
    void revokeDeviceTokensByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    void insertSsoToken(DesktopSsoToken token);
    DesktopSsoToken findUsableSsoToken(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
    int markSsoTokenUsed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
