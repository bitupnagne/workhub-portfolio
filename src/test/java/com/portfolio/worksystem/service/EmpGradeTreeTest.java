package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.model.EmpGrade;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 직급 조회는 직원 정보와 연차수당 계산에 계속 사용된다. */
class EmpGradeTreeTest {
    private final EmpGradeMapper mapper = mock(EmpGradeMapper.class);
    private final EmpGradeService service = new EmpGradeService(mapper);
    private final List<EmpGrade> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        nodes.add(node(10L, null, "3급", 1, 0));
        nodes.add(node(11L, 10L, "1호봉", 1, 2));
        nodes.add(node(12L, 10L, "2호봉", 2, 3));
        nodes.add(node(20L, null, "5급", 2, 0));
        nodes.add(node(21L, 20L, "1호봉", 1, 4));
        when(mapper.findAllWithUserCount()).thenReturn(nodes);
    }

    /* ═══ 트리 구성 ═══════════════════════════════════ */

    @Nested
    @DisplayName("트리")
    class Tree {

        @Test
        @DisplayName("부모 다음에 자식이 오는 순서로 펼친다")
        void preorder() {
            assertThat(service.tree()).extracting(EmpGrade::getName)
                    .containsExactly("3급", "1호봉", "2호봉", "5급", "1호봉");
        }

        @Test
        @DisplayName("서열은 위에서부터 1, 2, 3 …")
        void rankFollowsReadingOrder() {
            assertThat(service.tree()).extracting(EmpGrade::getRankOrder)
                    .containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("경로와 깊이를 채운다")
        void pathAndDepth() {
            EmpGrade second = service.tree().get(1);

            assertThat(second.getPathName()).isEqualTo("3급 › 1호봉");
            assertThat(second.getDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("같은 이름이라도 부모가 다르면 경로로 구분된다")
        void sameNameUnderDifferentParents() {
            assertThat(service.tree()).extracting(EmpGrade::getPathName)
                    .contains("3급 › 1호봉", "5급 › 1호봉");
        }

        @Test
        @DisplayName("하위 인원은 자손까지 합산한다")
        void descendantCountRollsUp() {
            EmpGrade third = service.tree().get(0);   // 3급: 본인 0 + 1호봉 2 + 2호봉 3

            assertThat(third.getDescendantUserCount()).isEqualTo(5);
            assertThat(third.isHasChildren()).isTrue();
        }

        /*
          부모가 사라진 노드를 버리면 화면에서 통째로 없어져 고칠 방법이 사라진다.
          최상위로 올려 붙여 보이게 둔다.
        */
        @Test
        @DisplayName("부모가 없어진 노드는 버리지 않고 최상위로 올린다")
        void orphansSurvive() {
            nodes.add(node(99L, 777L, "고아", 9, 1));

            assertThat(service.tree()).extracting(EmpGrade::getName).contains("고아");
        }
    }

    @Test
    void inactiveCurrentGradeRemainsSelectableWithoutChangingEmployeeData() {
        EmpGrade archived = node(90L, null, "이전 직급", 9, 1);
        archived.setActive(false);
        when(mapper.findActiveOrdered()).thenReturn(List.of(nodes.getFirst()));
        when(mapper.findById(90L)).thenReturn(archived);
        assertThat(service.selectableGrades(90L)).extracting(EmpGrade::getId).containsExactly(10L, 90L);
    }

    @Test
    void activeTreeRetainsInheritedPathsAndExcludesInactiveNodes() {
        nodes.get(2).setActive(false);
        assertThat(service.activeTree()).extracting(EmpGrade::getPathName)
                .containsExactly("3급", "3급 › 1호봉", "5급", "5급 › 1호봉");
    }

    @Test
    void unknownGradeIsRejectedWhenUpdatingEmployeeData() {
        assertThat(service.resolveGradeId(null)).isNull();
        assertThatThrownBy(() -> service.resolveGradeId(99L)).isInstanceOf(BusinessException.class);
    }

    private EmpGrade node(Long id, Long parentId, String name, int order, int users) {
        EmpGrade grade = new EmpGrade();
        grade.setId(id);
        grade.setParentId(parentId);
        grade.setName(name);
        grade.setDisplayOrder(order);
        grade.setActive(true);
        grade.setUserCount(users);
        return grade;
    }
}
