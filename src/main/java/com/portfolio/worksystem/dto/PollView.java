package com.portfolio.worksystem.dto;

import com.portfolio.worksystem.model.PollOption;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class PollView {
    private Long pollId;
    private String title;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private List<PollOption> options;
    private boolean open;            // 투표 가능 기간(시작~종료)
    private boolean resultsVisible;  // 종료일 이후 → 표수 공개
    private boolean voted;           // 현재 사용자 투표 여부
    private Long votedOptionId;      // 현재 사용자가 고른 항목(본인 것만)
    private long participantCount;   // 참여 인원
    private long totalEligible;      // 전체 대상 인원
    private long nonParticipantCount;
    private boolean locked;          // 1표 이상 → 수정 불가
    private boolean includeContractors;
}
