package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.model.EmpGrade;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 직원 정보와 연차수당 계산에서 사용하는 공용 직급 조회. */
@RequiredArgsConstructor
@Service
public class EmpGradeService {
    private final EmpGradeMapper empGradeMapper;

    /** 전체 등급(비활성 포함) — 정렬값 참조용. */
    @Transactional(readOnly = true)
    public List<EmpGrade> activeGradesAll() {
        return empGradeMapper.findAllWithUserCount();
    }

    @Transactional(readOnly = true)
    public List<EmpGrade> activeGrades() {
        return empGradeMapper.findActiveOrdered();
    }

    /** 직원 폼 셀렉트용: 활성 등급 + (비활성이어도) 현재 지정된 등급을 포함해 선택값 유실을 막는다. */
    @Transactional(readOnly = true)
    public List<EmpGrade> selectableGrades(Long currentGradeId) {
        List<EmpGrade> grades = new ArrayList<>(empGradeMapper.findActiveOrdered());
        if (currentGradeId != null && grades.stream().noneMatch(g -> currentGradeId.equals(g.getId()))) {
            EmpGrade current = empGradeMapper.findById(currentGradeId);
            if (current != null) {
                grades.add(current);
            }
        }
        return grades;
    }

    /** 등급 id 검증(직원 등록/수정에서 사용). null 허용, 존재하지 않으면 예외. */
    @Transactional(readOnly = true)
    public Long resolveGradeId(Long gradeId) {
        if (gradeId == null) {
            return null;
        }
        EmpGrade grade = empGradeMapper.findById(gradeId);
        if (grade == null) {
            throw new BusinessException("존재하지 않는 등급입니다.");
        }
        return grade.getId();
    }

    @Transactional(readOnly = true)
    public String gradeNameOfUser(Long userId) {
        return empGradeMapper.findGradeNameByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<EmpGrade> tree() {
        return buildTree(empGradeMapper.findAllWithUserCount());
    }

    /** 활성 노드만 트리 순서로. */
    @Transactional(readOnly = true)
    public List<EmpGrade> activeTree() {
        return buildTree(empGradeMapper.findAllWithUserCount()).stream()
                .filter(g -> !Boolean.FALSE.equals(g.getActive()))
                .toList();
    }

    /**
     * 부모→자식 순서로 펼치면서 깊이·경로·서열을 채운다.
     *
     * <p>부모가 없어진 노드(고아)는 버리지 않고 최상위로 올려 붙인다. 데이터가 깨졌다고
     * 화면에서 통째로 사라지면 고칠 방법이 없어진다.
     */
    private List<EmpGrade> buildTree(List<EmpGrade> all) {
        Map<Long, List<EmpGrade>> childrenOf = new LinkedHashMap<>();
        Set<Long> ids = all.stream().map(EmpGrade::getId).collect(Collectors.toSet());
        for (EmpGrade g : all) {
            Long parent = g.getParentId() != null && ids.contains(g.getParentId()) ? g.getParentId() : null;
            childrenOf.computeIfAbsent(parent, k -> new ArrayList<>()).add(g);
        }
        Comparator<EmpGrade> order = Comparator
                .comparing((EmpGrade g) -> g.getDisplayOrder() == null ? 0 : g.getDisplayOrder())
                .thenComparing(EmpGrade::getId);
        childrenOf.values().forEach(list -> list.sort(order));

        List<EmpGrade> out = new ArrayList<>();
        walk(childrenOf, null, 0, null, out);
        for (int i = 0; i < out.size(); i++) {
            out.get(i).setRankOrder(i + 1);
        }
        // 자손까지 합친 인원수 — 삭제 가드와 화면 표시에 쓴다.
        for (EmpGrade g : out) {
            g.setDescendantUserCount(subtreeIds(childrenOf, g.getId()).stream()
                    .mapToInt(id -> userCountOf(all, id))
                    .sum());
        }
        return out;
    }

    private void walk(Map<Long, List<EmpGrade>> childrenOf, Long parentId, int depth,
                      String parentPath, List<EmpGrade> out) {
        for (EmpGrade node : childrenOf.getOrDefault(parentId, List.of())) {
            node.setDepth(depth);
            node.setPathName(parentPath == null ? node.getName() : parentPath + " › " + node.getName());
            node.setHasChildren(!childrenOf.getOrDefault(node.getId(), List.of()).isEmpty());
            out.add(node);
            walk(childrenOf, node.getId(), depth + 1, node.getPathName(), out);
        }
    }

    private Set<Long> subtreeIds(Map<Long, List<EmpGrade>> childrenOf, Long rootId) {
        Set<Long> ids = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            Long id = stack.pop();
            if (!ids.add(id)) {
                continue;
            }
            for (EmpGrade child : childrenOf.getOrDefault(id, List.of())) {
                stack.push(child.getId());
            }
        }
        return ids;
    }

    private int userCountOf(List<EmpGrade> all, Long id) {
        return all.stream().filter(g -> g.getId().equals(id))
                .mapToInt(g -> g.getUserCount() == null ? 0 : g.getUserCount())
                .findFirst().orElse(0);
    }

}
