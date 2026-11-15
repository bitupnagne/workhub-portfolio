package com.portfolio.worksystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Data
public class ProjectForm {
    @NotBlank(message = "프로젝트명을 입력하세요.")
    @Size(max = 160, message = "프로젝트명은 160자까지 입력할 수 있습니다.")
    private String name;
    @Size(max = 10000, message = "프로젝트 설명은 10,000자까지 입력할 수 있습니다.")
    private String description;
    @NotNull(message = "책임자를 선택하세요.")
    private Long ownerId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;
    @Pattern(regexp = "ACTIVE|COMPLETED|ARCHIVED", message = "프로젝트 상태를 확인하세요.")
    private String status = "ACTIVE";
    private List<Long> memberIds = new ArrayList<>();
}
