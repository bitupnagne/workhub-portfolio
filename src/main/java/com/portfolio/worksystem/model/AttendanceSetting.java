package com.portfolio.worksystem.model;

import java.time.LocalTime;
import lombok.Data;

@Data
public class AttendanceSetting {
    private Integer id;
    private LocalTime defaultStartTime;
    private LocalTime defaultEndTime;
    private Long updatedBy;
}