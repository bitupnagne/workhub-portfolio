package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.ProjectTaskStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

@Data
public class ProjectTaskForm {
    @NotBlank(message = "업무명을 입력하세요.")
    @Size(max = 200, message = "업무명은 200자까지 입력할 수 있습니다.")
    private String title;
    @Size(max = 20000, message = "업무 설명은 20,000자까지 입력할 수 있습니다.")
    private String description;
    @NotNull(message = "담당자를 선택하세요.")
    private Long assigneeId;
    @NotNull(message = "업무 상태를 선택하세요.")
    private ProjectTaskStatus status = ProjectTaskStatus.TODO;
    @NotNull(message = "진척도를 입력하세요.")
    @Min(value = 0, message = "진척도는 0부터 100 사이로 입력하세요.")
    @Max(value = 100, message = "진척도는 0부터 100 사이로 입력하세요.")
    private Integer progress = 0;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;
    private List<Long> collaboratorIds = new ArrayList<>();
    private Long version;
}
