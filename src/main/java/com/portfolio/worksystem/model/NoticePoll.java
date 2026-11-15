package com.portfolio.worksystem.model;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class NoticePoll {
    private Long id;
    private Long noticeId;
    private String title;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Boolean includeContractors;
    private List<PollOption> options;
}
