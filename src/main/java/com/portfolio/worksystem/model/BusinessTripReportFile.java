package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import lombok.Data;

/** [UX47] 출장보고서 첨부(영수증 등) — 보고서 단계에서만 첨부. */
@Data
public class BusinessTripReportFile {
    private Long id;
    private Long reportId;
    private String storedFilename;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private LocalDateTime createdAt;
}
