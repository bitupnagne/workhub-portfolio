package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class DeploySsoLink {
    private Long id;
    private Long officeUserId;
    private String officeUsername;
    private Long deployUserId;
    private String deployLoginId;
    private String deployName;
    private Boolean active;
    private LocalDateTime linkedAt;
    private LocalDateTime updatedAt;
}
