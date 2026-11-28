package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectMapper {
    List<Project> findProjects(@Param("userId") Long userId, @Param("query") String query, @Param("today") LocalDate today);
    Project findProject(@Param("id") Long id, @Param("today") LocalDate today);
    Project lockProject(Long id);
    void insertProject(Project project);
    void updateProject(Project project);
    int memberCount(@Param("projectId") Long projectId, @Param("userId") Long userId);
    List<ProjectPerson> findPeople();
    List<ProjectPerson> findMembers(Long projectId);
    void insertMember(@Param("projectId") Long projectId, @Param("userId") Long userId, @Param("joinedAt") LocalDateTime joinedAt);
    void deleteMember(@Param("projectId") Long projectId, @Param("userId") Long userId);
    int countMemberReferences(@Param("projectId") Long projectId, @Param("userId") Long userId);
    int pendingHandoversInProject(Long projectId);
    int unfinishedTaskCount(Long projectId);
    List<ProjectTask> findTasks(Long projectId);
    ProjectTask findTask(Long taskId);
    ProjectTask lockTask(Long taskId);
    void insertTask(ProjectTask task);
    int updateTask(ProjectTask task);
    void transferTask(@Param("taskId") Long taskId, @Param("assigneeId") Long assigneeId, @Param("now") LocalDateTime now);
    List<ProjectPerson> findCollaborators(Long taskId);
    int collaboratorCount(@Param("taskId") Long taskId, @Param("userId") Long userId);
    void deleteCollaborators(Long taskId);
    void insertCollaborator(@Param("taskId") Long taskId, @Param("userId") Long userId);
    List<ProjectChecklist> findChecklist(Long taskId);
    ProjectChecklist findChecklistItem(Long itemId);
    void insertChecklist(ProjectChecklist item);
    void updateChecklist(@Param("id") Long id, @Param("completed") boolean completed);
    List<ProjectComment> findComments(Long taskId);
    void insertComment(ProjectComment comment);
    List<ProjectEvidence> findEvidence(Long taskId);
    ProjectEvidence findEvidenceById(Long id);
    void insertEvidence(ProjectEvidence evidence);
    ProjectHandover findPendingHandover(Long taskId);
    ProjectHandover findLatestHandover(Long taskId);
    List<ProjectHandover> findHandovers(Long taskId);
    ProjectHandover findHandover(Long id);
    void insertHandover(ProjectHandover handover);
    int respondHandover(ProjectHandover handover);
    List<ProjectActivity> findActivities(Long taskId);
    void insertActivity(ProjectActivity activity);
}
