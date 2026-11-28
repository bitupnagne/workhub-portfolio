package com.portfolio.worksystem.model;

import lombok.Data;

@Data
public class PollOption {
    private Long id;
    private Long pollId;
    private String optionText;
    private Integer sortOrder;
    private long voteCount;
}
