package com.portfolio.worksystem.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class BusinessTripForm {
    private LocalDate startDate;
    private LocalDate endDate;
    private String destination;
    private String purpose;
    /** [DLG] 결재자라면 출장 중 대리승인자(선택). */
    private Long delegateUserId;
    private List<BusinessTripExpenseForm> expenses = new ArrayList<>();
    private MultipartFile receiptFile;
}
