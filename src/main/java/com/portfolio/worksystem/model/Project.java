package com.portfolio.worksystem.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Project {
    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private String ownerName;
    private LocalDate startDate;
    private LocalDate dueDate;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int taskCount;
    private int doneTaskCount;
    private int overdueTaskCount;
    private int progress;
    private int memberCount;

    public String getStatusLabel() {
        return switch (status == null ? "ACTIVE" : status) {
            case "COMPLETED" -> "완료";
            case "ARCHIVED" -> "보관";
            default -> "진행 중";
        };
    }
}
