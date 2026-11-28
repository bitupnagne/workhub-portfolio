package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.PersonnelTransferMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.PersonnelTransfer;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인사이동: 발령일 기준 팀 변경 + 이력(연도별). 인사이동 기능으로 옮길 때만 이력을 남긴다.
 *  - 오늘/과거 발령일 = 즉시 반영(팀 변경). 미래 = 예약(applied=false) → 매일 스케줄러가 도래분 반영.
 *  - 이력 삭제/날짜수정 가능. 삭제 시 현재 팀은 유지.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class PersonnelTransferService {

    private final PersonnelTransferMapper transferMapper;
    private final UserMapper userMapper;
    /** [B9] 인사정보 변경이력. */
    private final UserChangeLogService userChangeLogService;
    private final TeamMapper teamMapper;
    private final EmpGradeService empGradeService;
    private final PresenceCacheService presenceCacheService;

    @Transactional(readOnly = true)
    public List<PersonnelTransfer> history(Integer year) {
        return transferMapper.findByYear(year == null ? LocalDate.now().getYear() : year);
    }

    /** 한 사용자의 해당 연도 '반영된' 인사이동(발령일 오름차순). */
    @Transactional(readOnly = true)
    public List<PersonnelTransfer> appliedForUser(Long userId, Integer year) {
        return transferMapper.findAppliedByUserYear(userId, year);
    }

    /** [B5] 인사카드 발령 이력 — 사용자 전체 이력(최신순). */
    @Transactional(readOnly = true)
    public List<PersonnelTransfer> historyForUser(Long userId) {
        return transferMapper.findByUser(userId);
    }

    @Transactional
    public Long transfer(Long userId, Long toTeamId, LocalDate effectiveDate, CustomUserDetails currentUser) {
        requireManager(currentUser);
        if (userId == null || toTeamId == null || effectiveDate == null) {
            throw new BusinessException("조직원·대상 팀·발령일을 모두 선택하세요.");
        }
        UserAccount u = userMapper.findById(userId);
        if (u == null || !Boolean.TRUE.equals(u.getActive())) {
            throw new BusinessException("대상 조직원을 찾을 수 없습니다.");
        }
        Team to = teamMapper.findById(toTeamId);
        if (to == null || !Boolean.TRUE.equals(to.getActive())) {
            throw new BusinessException("인사이동 대상 팀을 찾을 수 없습니다.");
        }
        if (toTeamId.equals(u.getTeamId())) {
            throw new BusinessException("이미 해당 팀 소속입니다.");
        }
        PersonnelTransfer t = new PersonnelTransfer();
        t.setUserId(userId);
        t.setFromTeamId(u.getTeamId());
        t.setToTeamId(toTeamId);
        t.setEffectiveDate(effectiveDate);
        t.setTransferYear(effectiveDate.getYear());
        boolean immediate = !effectiveDate.isAfter(LocalDate.now()); // 오늘/과거 = 즉시
        t.setApplied(immediate);
        t.setCreatedBy(currentUser.getUserId());
        transferMapper.insert(t);
        if (immediate) {
            applyTeamChange(userId, toTeamId);
        }
        log.info("[인사이동] user={} {}->{} 발령일={} {}", userId, t.getFromTeamId(), toTeamId, effectiveDate,
                immediate ? "즉시반영" : "예약");
        return t.getId();
    }

    @Transactional
    public void updateDate(Long id, LocalDate newDate, CustomUserDetails currentUser) {
        requireManager(currentUser);
        PersonnelTransfer t = transferMapper.findById(id);
        if (t == null) {
            throw new BusinessException("인사이동 이력을 찾을 수 없습니다.");
        }
        if (newDate == null) {
            throw new BusinessException("발령일을 입력하세요.");
        }
        transferMapper.updateEffectiveDate(id, newDate, newDate.getYear());
        // 미반영(예약) 건인데 새 발령일이 과거/오늘이면 즉시 반영. 이미 반영된 건의 팀은 그대로(원복 안 함).
        if (!t.isApplied() && !newDate.isAfter(LocalDate.now())) {
            applyTeamChange(t.getUserId(), t.getToTeamId());
            transferMapper.markApplied(id);
        }
    }

    /** 이력 삭제 — '삭제합니다' 타이핑. 현재 팀은 유지. 미반영 예약건은 스케줄 취소 효과. */
    @Transactional
    public void delete(Long id, String confirmText, CustomUserDetails currentUser) {
        requireManager(currentUser);
        if (!"삭제합니다".equals(confirmText == null ? "" : confirmText.trim())) {
            throw new BusinessException("확인 문구('삭제합니다')가 일치하지 않습니다.");
        }
        if (transferMapper.findById(id) == null) {
            throw new BusinessException("인사이동 이력을 찾을 수 없습니다.");
        }
        transferMapper.delete(id);
    }

    /** 매일 00:05 — 발령일 도래(미래 예약)분을 실제 팀 변경으로 반영. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public void applyDueTransfers() {
        List<PersonnelTransfer> due = transferMapper.findDuePending(LocalDate.now());
        for (PersonnelTransfer t : due) {
            applyTeamChange(t.getUserId(), t.getToTeamId());
            transferMapper.markApplied(t.getId());
            log.info("[인사이동] 예약 발령 반영: user={} -> team={} (발령일 {})", t.getUserId(), t.getToTeamId(), t.getEffectiveDate());
        }
    }

    private void applyTeamChange(Long userId, Long toTeamId) {
        // [B9] 인사카드 이력에 남긴다 — 예약 발령이 스케줄러로 반영될 때도 흔적이 있어야 한다.
        UserAccount before = userMapper.findById(userId);
        userMapper.updateUserTeam(userId, toTeamId);
        presenceCacheService.evictAll(); // 조직트리/재실현황 캐시 즉시 반영
        UserAccount after = userMapper.findById(userId);
        if (before != null && after != null) {
            userChangeLogService.recordEvent(userId, "teamName", "소속 (인사이동)",
                    before.getTeamName(), after.getTeamName(), "TRANSFER", null);
        }
    }

    private void requireManager(CustomUserDetails currentUser) {
        if (!empGradeService.canManageGrades(currentUser)) {
            throw new BusinessException("인사 관리 권한이 없습니다.");
        }
    }
}
