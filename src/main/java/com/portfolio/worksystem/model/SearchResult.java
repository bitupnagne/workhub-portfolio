package com.portfolio.worksystem.model;

import java.time.LocalDate;
import lombok.Data;

@Data
public class SearchResult {
    private String weekKey;
    private Long userId;
    private String teamName;
    private String memberName;
    private Long itemId;
    private String content;
    private String parentContent;
    private String type;
    private ItemStatus status;
    private LocalDate dueDate;
    private String cycle;
}
