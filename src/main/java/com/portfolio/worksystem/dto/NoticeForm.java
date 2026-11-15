package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Data
public class NoticeForm {
    private Long id;
    @NotBlank
    private String title;
    @NotBlank
    private String body;
    private String linkUrl;
    private Boolean sendNotification = false;
    private Boolean allowAnonymous = false;

    private Boolean pollEnabled = false;
    private Boolean pollIncludeContractors = true;
    private String pollTitle;
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime pollStartDate;
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime pollEndDate;
    private List<String> pollOptions = new ArrayList<>();

    public boolean shouldSendNotification() {
        return Boolean.TRUE.equals(sendNotification);
    }

    public boolean isAllowAnonymousValue() {
        return Boolean.TRUE.equals(allowAnonymous);
    }

    public boolean isPollIncludeContractorsValue() {
        return !Boolean.FALSE.equals(pollIncludeContractors);
    }
}
