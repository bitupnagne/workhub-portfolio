package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.CommunicationMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.model.EmployeePresence;
import com.portfolio.worksystem.model.Team;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * [S-4] 자주 호출되는 무거운 쿼리를 별도 서비스로 분리해서 @Cacheable 적용.
 *  self-invocation에선 @Cacheable이 동작하지 않으므로 다른 서비스에서 이 빈을 inject해서 호출해야 한다.
 *
 *  - employeePresence: 3초 TTL. 출근/퇴근/휴가/출장/공휴일 변경 시 evictPresence() 호출.
 *  - orgTree: 60초 TTL. 조직 트리 변경 시 evictOrgTree() 호출.
 */
@RequiredArgsConstructor
@Service
public class PresenceCacheService {
    private final CommunicationMapper communicationMapper;
    private final TeamMapper teamMapper;

    @Cacheable(value = "employeePresence", key = "#date")
    public List<EmployeePresence> employeePresence(LocalDate date) {
        return communicationMapper.findEmployeePresence(date);
    }

    @Cacheable(value = "orgTree")
    public List<Team> orgTree() {
        return teamMapper.findAllNodes();
    }

    /** 출근/퇴근/휴가/출장/공휴일 등 presence 영향 이벤트마다 호출. */
    @CacheEvict(value = "employeePresence", allEntries = true)
    public void evictPresence() {
    }

    /** 조직 구조(팀/노드/리더) 변경 시 호출. */
    @CacheEvict(value = "orgTree", allEntries = true)
    public void evictOrgTree() {
    }

    /** 직원 변경(가입/탈퇴/팀이동)은 트리·presence 모두에 영향. */
    @CacheEvict(value = {"employeePresence", "orgTree"}, allEntries = true)
    public void evictAll() {
    }
}
