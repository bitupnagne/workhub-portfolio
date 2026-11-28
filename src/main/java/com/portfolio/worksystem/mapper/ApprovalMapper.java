package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.ApprovalDelegation;
import com.portfolio.worksystem.model.ApprovalDocumentSnapshot;
import com.portfolio.worksystem.model.ApprovalDocumentType;
import com.portfolio.worksystem.model.ApprovalInboxItem;
import com.portfolio.worksystem.model.ApprovalRouteRule;
import com.portfolio.worksystem.model.ApprovalStatus;
import com.portfolio.worksystem.model.ApprovalStep;
import com.portfolio.worksystem.model.RequesterLevel;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalMapper {
    List<ApprovalRouteRule> findRouteRules(@Param("documentType") ApprovalDocumentType documentType,
                                           @Param("requesterLevel") RequesterLevel requesterLevel);
    List<ApprovalRouteRule> findRouteRulesByScope(@Param("documentType") ApprovalDocumentType documentType,
                                                  @Param("requesterLevel") RequesterLevel requesterLevel,
                                                  @Param("scopeTeamId") Long scopeTeamId);
    List<ApprovalRouteRule> findAllRouteRules();
    ApprovalRouteRule findRouteRule(Long id);
    void insertRouteRule(ApprovalRouteRule rule);
    void updateRouteRule(ApprovalRouteRule rule);
    void deleteRouteRule(Long id);
    Long findRouteRuleId(@Param("documentType") ApprovalDocumentType documentType,
                         @Param("requesterLevel") RequesterLevel requesterLevel,
                         @Param("scopeTeamId") Long scopeTeamId,
                         @Param("stepOrder") Integer stepOrder);
    List<Long> findRouteReceiverTeamIds(Long ruleId);
    void deleteRouteReceivers(Long ruleId);
    void insertRouteReceiver(@Param("ruleId") Long ruleId, @Param("teamId") Long teamId);
    void insertDocumentReceiver(@Param("documentType") ApprovalDocumentType documentType,
                                @Param("documentId") Long documentId,
                                @Param("receiverTeamId") Long receiverTeamId);
    void deleteDocumentReceivers(@Param("documentType") ApprovalDocumentType documentType,
                                 @Param("documentId") Long documentId);
    boolean existsDocumentReceiver(@Param("documentType") ApprovalDocumentType documentType,
                                   @Param("documentId") Long documentId,
                                   @Param("teamId") Long teamId);

    /** [R-1] 결재 문서의 신청자 team_id 조회 (같은 팀원 view 권한 검사용) */
    Long findRequesterTeamId(@Param("documentType") ApprovalDocumentType documentType,
                             @Param("documentId") Long documentId);

    List<ApprovalStep> findSteps(@Param("documentType") ApprovalDocumentType documentType,
                                 @Param("documentId") Long documentId);
    ApprovalStep findPendingStep(@Param("documentType") ApprovalDocumentType documentType,
                                 @Param("documentId") Long documentId);
    void insertStep(ApprovalStep step);
    void updateStepStatus(@Param("stepId") Long stepId,
                          @Param("status") ApprovalStatus status,
                          @Param("actedBy") Long actedBy,
                          @Param("rejectedReason") String rejectedReason);
    ApprovalStep findPostApprovalStep(@Param("documentType") ApprovalDocumentType documentType,
                                      @Param("documentId") Long documentId,
                                      @Param("approverUserId") Long approverUserId);
    void updatePostApprovalStep(@Param("stepId") Long stepId,
                                @Param("postApprovedBy") Long postApprovedBy);
    void deleteSteps(@Param("documentType") ApprovalDocumentType documentType,
                     @Param("documentId") Long documentId);
    List<ApprovalInboxItem> findInboxItems(@Param("userId") Long userId);
    List<ApprovalInboxItem> findDocumentItems(@Param("userId") Long userId,
                                              @Param("teamId") Long teamId,
                                              @Param("box") String box,
                                              @Param("superAdmin") boolean superAdmin);
    ApprovalDocumentSnapshot findDocumentSnapshot(@Param("documentType") ApprovalDocumentType documentType,
                                                  @Param("documentId") Long documentId);
    /** 보관문서 파일 일괄 재생성용 — teamName 이 null/빈값이면 전체. */
    List<ApprovalDocumentSnapshot> findSnapshotsByTeam(@Param("teamName") String teamName);
    void insertDocumentSnapshot(ApprovalDocumentSnapshot snapshot);
    void updateDocumentSnapshotFile(ApprovalDocumentSnapshot snapshot);
    void updateDocumentSnapshotHtml(ApprovalDocumentSnapshot snapshot);

    List<ApprovalDelegation> findDelegations(Long approverUserId);
    List<ApprovalDelegation> findActiveDelegationsForUser(@Param("approverUserId") Long approverUserId,
                                                          @Param("startDate") LocalDate startDate,
                                                          @Param("endDate") LocalDate endDate);
    void insertDelegation(ApprovalDelegation delegation);
    void deactivateDelegation(@Param("id") Long id, @Param("approverUserId") Long approverUserId);

    /** [DLG] 부재 신청에 묶인 '예정' 대리 지정. */
    void insertLinkedDelegation(ApprovalDelegation delegation);

    void activateLinkedDelegation(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    void deleteLinkedDelegation(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    /** [DLG] 결재 대기 단계의 대리를 오늘 기준으로 다시 붙인다. */
    int refreshPendingDelegates(@Param("today") java.time.LocalDate today);

    int countApproverRoles(@Param("userId") Long userId);
}
