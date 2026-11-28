package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DesktopSsoToken {
    private Long id;
    private Long userId;
    private String tokenHash;
    private String redirectPath;
    private boolean otpVerified;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
}
