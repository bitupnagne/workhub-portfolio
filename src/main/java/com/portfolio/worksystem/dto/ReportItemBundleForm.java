package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.ItemStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ReportItemBundleForm {
    private Long id;
    private String content;
    private LocalDate dueDate;
    private String cycle;
    private ItemStatus status;
    private String weekKey;
    private List<Detail> details = new ArrayList<>();

    @Data
    public static class Detail {
        private Long id;
        private String text;
        private LocalDate dueDate;
        private String cycle;
    }
}
