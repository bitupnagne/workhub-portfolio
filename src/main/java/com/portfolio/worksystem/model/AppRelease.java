package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class AppRelease {
    private Long id;
    private String version;
    private String fileName;
    private String storedFileName;
    private String sha256;
    private Long fileSize;
    private Boolean mandatory;
    private String patchBaseVersion;
    private String patchFileName;
    private String patchStoredFileName;
    private String patchSha256;
    private Long patchFileSize;
    private List<AppReleasePatch> patches = new ArrayList<>();
    private String releaseNote;
    private Boolean active;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
