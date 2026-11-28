package com.portfolio.worksystem.model;

import java.time.LocalDate;
import lombok.Data;

@Data
public class CompanyHoliday {
    private Long id;
    private LocalDate holidayDate;
    private String name;
    /** COMPANY | PUBLIC */
    private String category;
    private Long createdBy;
    private String createdByName;

    public boolean isPublicHoliday() { return "PUBLIC".equalsIgnoreCase(category); }
}
