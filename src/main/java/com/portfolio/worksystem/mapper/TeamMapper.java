package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.Team;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TeamMapper {
    List<Team> findAll();
    List<Team> findAllNodes();
    List<Team> findAncestors(Long teamId);
    Team findById(Long id);
    Team findTopLedNonTeam(@org.apache.ibatis.annotations.Param("userId") Long userId);
    Team findByCode(String code);
    String getSetting(String key);
    void setSetting(@Param("key") String key, @Param("value") String value);
    void insertTeam(Team team);
    int countActiveChildren(Long teamId);
    void updateTeamLeader(@Param("teamId") Long teamId, @Param("leaderUserId") Long leaderUserId);
    void updateTeamName(@Param("teamId") Long teamId, @Param("name") String name);
    void updateWeeklyReportTarget(@Param("teamId") Long teamId, @Param("target") boolean target);
    void clearLeaderByUserId(Long userId);
    void deactivateTeam(Long teamId);
    void updateTeamLeaderRole(@Param("teamId") Long teamId, @Param("leaderUserId") Long leaderUserId);
    void updateDisplayOrder(@Param("teamId") Long teamId, @Param("displayOrder") int displayOrder);
}
