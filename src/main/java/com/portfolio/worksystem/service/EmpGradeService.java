package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
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
import org.springframework.util.StringUtils;

/** 직급 마스터(emp_grades) 트리 관리 — 값·단위·순서와 직원 직급 지정. */
@RequiredArgsConstructor
@Service
public class EmpGradeService {

    private final EmpGradeMapper empGradeMapper;
    private final UserMapper userMapper;
    private final com.portfolio.worksystem.mapper.TeamMapper teamMapper;

    public boolean canManageGrades(CustomUserDetails currentUser) {
        return currentUser != null
                && (currentUser.isSystemAdmin() || currentUser.hasRole("HR_ADMIN"));
    }

    @Transactional(readOnly = true)
    public List<EmpGrade> grades(CustomUserDetails currentUser) {
        requireManager(currentUser);
        return empGradeMapper.findAllWithUserCount();
    }

    /** [D5] 관리 화면용 — 트리 순서 + 상속·서열이 채워진 목록. */
    @Transactional(readOnly = true)
    public List<EmpGrade> gradeTree(CustomUserDetails currentUser) {
        requireManager(currentUser);
        return tree();
    }

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

    /**
     * [D5] 직급 추가. {@code parentId} 를 주면 그 아래(호봉 등)로 붙는다.
     */
    @Transactional
    public void createGrade(String name, Integer displayOrder, Long parentId,
                            CustomUserDetails currentUser) {
        createGrade(name, null, displayOrder, parentId, currentUser);
    }

    /**
     * [GRADE] 값 + 단위로 직급을 만든다. 값·단위 모두 자유 텍스트다('3'+'급', '선임'+'').
     *
     * @param unitLabel null 이면 단계 기본 단위를 따르고, 빈 문자열이면 단위를 붙이지 않는다.
     */
    @Transactional
    public void createGrade(String value, String unitLabel, Integer displayOrder, Long parentId,
                            CustomUserDetails currentUser) {
        requireManager(currentUser);
        if (parentId != null) {
            getGrade(parentId);
        }
        EmpGrade grade = new EmpGrade();
        grade.setParentId(parentId);
        grade.setGradeValue(requireValue(value));
        grade.setUnitLabel(normalizeUnit(unitLabel));
        grade.setName(requireName(composeName(grade.getGradeValue(), unitFor(grade.getUnitLabel(), depthOfChild(parentId))),
                null, parentId));
        grade.setDisplayOrder(displayOrder == null ? nextDisplayOrder(parentId) : displayOrder);
        grade.setActive(true);
        empGradeMapper.insert(grade);
    }

    @Transactional
    public void updateGrade(Long id, String name, Integer displayOrder, boolean active,
                            Long parentId, CustomUserDetails currentUser) {
        updateGrade(id, name, KEEP_UNIT, displayOrder, active, parentId, currentUser);
    }

    /** 단위 칸을 보내지 않은 옛 호출 — 저장된 단위를 그대로 둔다. */
    private static final String KEEP_UNIT = "\u0000keep";

    @Transactional
    public void updateGrade(Long id, String value, String unitLabel, Integer displayOrder,
                            boolean active, Long parentId, CustomUserDetails currentUser) {
        requireManager(currentUser);
        EmpGrade grade = getGrade(id);
        requireNotOwnDescendant(id, parentId);
        boolean moved = !java.util.Objects.equals(grade.getParentId(), parentId);
        grade.setParentId(parentId);
        grade.setGradeValue(requireValue(value));
        if (!KEEP_UNIT.equals(unitLabel)) {
            grade.setUnitLabel(normalizeUnit(unitLabel));
        }
        grade.setName(requireName(composeName(grade.getGradeValue(), unitFor(grade.getUnitLabel(), depthOfChild(parentId))),
                id, parentId));
        grade.setDisplayOrder(displayOrder == null ? grade.getDisplayOrder() : displayOrder);
        grade.setActive(active);
        empGradeMapper.update(grade);
        if (moved) {
            // 다른 단계로 옮기면 아래 직급들의 단계 기본 단위도 바뀐다.
            recomputeNames();
        }
    }

    /* ── [GRADE] 단계별 단위 ─────────────────────────────── */

    /** 단계(0=최상위) → 기본 단위. */
    @Transactional(readOnly = true)
    public Map<Integer, String> depthUnits() {
        Map<Integer, String> units = new java.util.TreeMap<>();
        for (Map<String, Object> row : empGradeMapper.findDepthUnits()) {
            Object depth = row.get("depth") != null ? row.get("depth") : row.get("DEPTH");
            Object unit = row.get("unit_label") != null ? row.get("unit_label") : row.get("UNIT_LABEL");
            if (depth instanceof Number number && unit != null) {
                units.put(number.intValue(), unit.toString());
            }
        }
        return units;
    }

    /**
     * 단계별 기본 단위 저장 — 빈 칸은 '단위 없음'. 저장 뒤 그 단계 직급들의 보이는 이름을 다시 만든다.
     * 다시 만든 이름이 형제끼리 겹치면 저장하지 않는다(트랜잭션 전체 취소).
     */
    @Transactional
    public String saveDepthUnits(Map<Integer, String> units, CustomUserDetails currentUser) {
        requireManager(currentUser);
        for (Map.Entry<Integer, String> entry : units.entrySet()) {
            int depth = entry.getKey();
            if (depth < 0 || depth > 20) {
                continue;
            }
            String unit = entry.getValue() == null ? "" : entry.getValue().trim();
            if (unit.length() > 20) {
                throw new BusinessException((depth + 1) + "단계 단위는 20자 이내로 입력하세요.");
            }
            if (unit.isEmpty()) {
                empGradeMapper.deleteDepthUnit(depth);
            } else {
                empGradeMapper.upsertDepthUnit(depth, unit, currentUser.getUserId());
            }
        }
        int changed = recomputeNames();
        return "단계별 단위를 저장했습니다." + (changed > 0 ? " 직급 이름 " + changed + "개가 바뀌었습니다." : "");
    }

    /**
     * 같은 부모 아래에서 한 칸 위/아래로 — 서열(순위)은 이 순서와 트리 위치로 정해진다.
     * 형제 순서를 1부터 다시 매겨 중간에 빈 번호·겹친 번호가 남지 않게 한다.
     */
    @Transactional
    public void moveGrade(Long id, boolean up, CustomUserDetails currentUser) {
        requireManager(currentUser);
        EmpGrade target = getGrade(id);
        List<EmpGrade> siblings = new ArrayList<>(tree().stream()
                .filter(g -> java.util.Objects.equals(g.getParentId(), target.getParentId()))
                .toList());
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(id)) {
                index = i;
            }
        }
        int swap = up ? index - 1 : index + 1;
        if (index < 0 || swap < 0 || swap >= siblings.size()) {
            return;
        }
        java.util.Collections.swap(siblings, index, swap);
        for (int i = 0; i < siblings.size(); i++) {
            empGradeMapper.updateDisplayOrder(siblings.get(i).getId(), i + 1);
        }
    }

    /**
     * 모든 직급의 보이는 이름을 값+단위로 다시 만든다. 형제끼리 같은 이름이 생기면 막는다.
     *
     * @return 이름이 바뀐 직급 수
     */
    private int recomputeNames() {
        Map<Integer, String> units = depthUnits();
        List<EmpGrade> nodes = tree();
        Map<String, Long> seen = new java.util.HashMap<>();
        int changed = 0;
        for (EmpGrade node : nodes) {
            String value = StringUtils.hasText(node.getGradeValue()) ? node.getGradeValue() : node.getName();
            String unit = node.getUnitLabel() != null ? node.getUnitLabel() : units.getOrDefault(node.getDepth(), "");
            String name = composeName(value, unit);
            String key = node.getParentId() + "\u0000" + name;
            Long other = seen.putIfAbsent(key, node.getId());
            if (other != null) {
                throw new BusinessException("같은 상위 아래에 '" + name + "' 이(가) 두 개 생깁니다. 값이나 단위를 바꿔 주세요.");
            }
            if (!name.equals(node.getName())) {
                empGradeMapper.updateName(node.getId(), name);
                changed++;
            }
        }
        return changed;
    }

    private static String composeName(String value, String unit) {
        return value + (unit == null ? "" : unit);
    }

    /** 이 노드에 붙을 단위 — 노드 지정이 있으면 그것, 없으면 그 단계의 기본 단위. */
    private String unitFor(String nodeUnit, int depth) {
        if (nodeUnit != null) {
            return nodeUnit;
        }
        return depthUnits().getOrDefault(depth, "");
    }

    /** parentId 아래에 붙을 노드의 단계(0=최상위). */
    private int depthOfChild(Long parentId) {
        if (parentId == null) {
            return 0;
        }
        return tree().stream().filter(g -> g.getId().equals(parentId)).findFirst()
                .map(g -> g.getDepth() + 1).orElse(1);
    }

    private static String requireValue(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("직급 값을 입력하세요(숫자·글자 모두 됩니다).");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 50) {
            throw new BusinessException("직급 값은 50자 이내로 입력하세요.");
        }
        return trimmed;
    }

    /** 단위 입력 — null 은 '단계 기본', 빈 문자열은 '단위 없음'. */
    private static String normalizeUnit(String unit) {
        if (unit == null) {
            return null;
        }
        String trimmed = unit.trim();
        if (trimmed.length() > 20) {
            throw new BusinessException("단위는 20자 이내로 입력하세요.");
        }
        return trimmed;
    }

    /**
     * [D5] 삭제 — 하위가 있으면 막고, 인원은 <b>자손까지</b> 센다.
     *
     * <p>예전에는 그 노드에 직접 달린 인원만 봤다. 트리에서는 '3급'을 지워도 그 아래 호봉에
     * 사람이 남아 있을 수 있는데, 그러면 FK 로 막히거나 고아 노드가 생긴다.
     */
    @Transactional
    public void deleteGrade(Long id, CustomUserDetails currentUser) {
        requireManager(currentUser);
        EmpGrade target = getGrade(id);
        List<EmpGrade> nodes = tree();
        List<Long> subtree = nodes.stream()
                .filter(g -> g.getId().equals(id) || isDescendantOf(nodes, g.getId(), id))
                .map(EmpGrade::getId)
                .toList();
        if (subtree.size() > 1) {
            throw new BusinessException("'" + target.getName() + "' 아래에 하위 직급이 "
                    + (subtree.size() - 1) + "개 있어 삭제할 수 없습니다. 하위를 먼저 옮기거나 삭제하세요.");
        }
        int inUse = empGradeMapper.countUsersByGradeIds(subtree);
        if (inUse > 0) {
            throw new BusinessException("이 등급으로 지정된 직원이 " + inUse + "명 있어 삭제할 수 없습니다. 비활성 처리하거나 직원 등급을 먼저 변경하세요.");
        }
        empGradeMapper.delete(id);
    }

    /**
     * 자기 자신이나 자기 자손을 부모로 삼으면 트리가 고리가 되어 순회가 끝나지 않는다.
     */
    private void requireNotOwnDescendant(Long id, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(id)) {
            throw new BusinessException("자기 자신을 상위 직급으로 지정할 수 없습니다.");
        }
        getGrade(parentId);
        if (isDescendantOf(tree(), parentId, id)) {
            throw new BusinessException("하위 직급을 상위로 지정할 수 없습니다(순환).");
        }
    }

    /** candidate 가 ancestorId 의 자손인지. */
    private boolean isDescendantOf(List<EmpGrade> nodes, Long candidate, Long ancestorId) {
        Map<Long, Long> parentOf = new LinkedHashMap<>();
        nodes.forEach(g -> parentOf.put(g.getId(), g.getParentId()));
        Long cursor = parentOf.get(candidate);
        int guard = 0;
        while (cursor != null && guard++ < 100) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            cursor = parentOf.get(cursor);
        }
        return false;
    }

    /**
     * 조직도 기준 일괄 지정(미지정자만): 본부장→1급, 팀장→3급, 나머지→5급.
     * 대표이사·기타임원은 자동 추정이 불가능하므로 수동 지정 유지.
     */
    @Transactional
    public String bulkAssignByOrg(CustomUserDetails currentUser) {
        requireManager(currentUser);
        EmpGrade g1 = empGradeMapper.findByName("1급");
        EmpGrade g3 = empGradeMapper.findByName("3급");
        EmpGrade g5 = empGradeMapper.findByName("5급");
        if (g1 == null || g3 == null || g5 == null) {
            throw new BusinessException("1급/3급/5급 등급이 존재해야 일괄 지정할 수 있습니다.");
        }
        Set<Long> divisionLeaders = new java.util.HashSet<>();
        Set<Long> teamLeaders = new java.util.HashSet<>();
        for (com.portfolio.worksystem.model.Team node : teamMapper.findAllNodes()) {
            if (!Boolean.TRUE.equals(node.getActive()) || node.getLeaderUserId() == null) continue;
            if ("DIVISION".equals(node.getNodeType())) divisionLeaders.add(node.getLeaderUserId());
            else if ("TEAM".equals(node.getNodeType())) teamLeaders.add(node.getLeaderUserId());
        }
        int division = 0;
        int team = 0;
        int member = 0;
        for (UserAccount user : userMapper.findAllActive()) {
            if (Boolean.TRUE.equals(user.getSystemAccount()) || user.getGradeId() != null) continue;
            if (divisionLeaders.contains(user.getId())) {
                empGradeMapper.updateUserGrade(user.getId(), g1.getId());
                division++;
            } else if (teamLeaders.contains(user.getId())) {
                empGradeMapper.updateUserGrade(user.getId(), g3.getId());
                team++;
            } else {
                empGradeMapper.updateUserGrade(user.getId(), g5.getId());
                member++;
            }
        }
        return "본부장 " + division + "명→1급, 팀장 " + team + "명→3급, 팀원 " + member + "명→5급 지정 완료 (기존 지정자는 유지, 대표이사·기타임원은 수동 지정 필요)";
    }

    @Transactional(readOnly = true)
    public Set<Long> orgLeaderIds() {
        return Set.copyOf(empGradeMapper.findLeaderUserIds());
    }

    /* ── [D5] 직급 트리 ─────────────────────────────────── */

    /**
     * 전체 직급을 <b>트리 순서</b>(위에서 아래로 읽는 순서)로 펼쳐 돌려준다.
     *
     * <p>각 노드에 depth·경로·전체 서열을 채워 둔다.
     */
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
     * 부모→자식 순서로 펼치면서 상속·깊이·경로·서열을 채운다.
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
        Map<Integer, String> units = depthUnitsQuietly();
        for (EmpGrade g : out) {
            g.setUnitOverridden(g.getUnitLabel() != null);
            g.setEffectiveUnit(g.getUnitLabel() != null ? g.getUnitLabel() : units.getOrDefault(g.getDepth(), ""));
            if (!StringUtils.hasText(g.getGradeValue())) {
                g.setGradeValue(g.getName());
            }
        }
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

    /** 화면 표시용 — 단위 테이블이 아직 없는 DB(마이그레이션 전 테스트 등)에서도 트리는 그려야 한다. */
    private Map<Integer, String> depthUnitsQuietly() {
        try {
            return depthUnits();
        } catch (RuntimeException e) {
            return Map.of();
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

    private EmpGrade getGrade(Long id) {
        EmpGrade grade = empGradeMapper.findById(id);
        if (grade == null) {
            throw new BusinessException("등급을 찾을 수 없습니다.");
        }
        return grade;
    }

    /**
     * 이름은 <b>형제 사이에서만</b> 유일하다 — '1호봉'은 급마다 있어야 한다.
     *
     * <p>DB 의 전역 UNIQUE 는 V83 에서 없앴다. MariaDB 의 UNIQUE 는 NULL 을 서로 다른 값으로
     * 보기 때문에 (parent_id, name) 유니크로는 최상위 형제가 안 걸린다 — 그래서 여기서 본다.
     */
    private String requireName(String name, Long selfId, Long parentId) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("등급 이름을 입력하세요.");
        }
        String trimmed = name.trim();
        EmpGrade dup = empGradeMapper.findSiblingByName(parentId, trimmed);
        if (dup != null && !dup.getId().equals(selfId)) {
            throw new BusinessException("같은 상위 아래에 '" + trimmed + "' 이(가) 이미 있습니다.");
        }
        return trimmed;
    }

    /** 순서는 <b>형제 안에서만</b> 의미가 있다. 전체 서열은 트리를 훑어 매긴다. */
    private int nextDisplayOrder(Long parentId) {
        Integer max = empGradeMapper.maxDisplayOrderOfSiblings(parentId);
        return (max == null ? 0 : max) + 1;
    }

    private void requireManager(CustomUserDetails currentUser) {
        if (!canManageGrades(currentUser)) {
            throw new BusinessException("직급 관리 권한이 없습니다.");
        }
    }
}
