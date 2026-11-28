package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApproverType;
import com.portfolio.worksystem.model.RequesterLevel;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class ApprovalRouteChainForm {
    @NotNull
    private ApprovalDocumentType documentType;
    private Long scopeTeamId;
    @NotNull
    private RequesterLevel requesterLevel;
    private Boolean active = true;
    private List<Step> steps = new ArrayList<>();
    private List<Long> receiverTeamIds = new ArrayList<>();

    public boolean isActiveValue() {
        return Boolean.TRUE.equals(active);
    }

    @Data
    public static class Step {
        private ApproverType approverType;
        private Long approverUserId;
        private Long approverTeamId;
    }
}
