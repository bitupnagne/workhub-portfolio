package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.EmpGradeMapper;
import com.portfolio.worksystem.mapper.TeamMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.EmpGrade;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * [D5] 직급을 <b>무한 트리</b>로 — '급' 아래 '호봉', 그 아래로도 계속.
 *
 * <p>트리 순서·경로·서열과 편집 가드(순환, 형제 이름 중복, 삭제 조건)를 고정한다.
 */
class EmpGradeTreeTest {

    private final EmpGradeMapper gradeMapper = mock(EmpGradeMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final TeamMapper teamMapper = mock(TeamMapper.class);

    private final EmpGradeService service = new EmpGradeService(gradeMapper, userMapper, teamMapper);

    /** 3급 ─ 1호봉 · 2호봉 / 5급 ─ 1호봉 */
    private final List<EmpGrade> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        nodes.clear();
        nodes.add(node(10L, null, "3급", 1, 0));
        nodes.add(node(11L, 10L, "1호봉", 1, 2));
        nodes.add(node(12L, 10L, "2호봉", 2, 3));
        nodes.add(node(20L, null, "5급", 2, 0));
        nodes.add(node(21L, 20L, "1호봉", 1, 4));
        lenient().when(gradeMapper.findAllWithUserCount()).thenReturn(nodes);
        lenient().when(gradeMapper.findLeaderUserIds()).thenReturn(List.of());
        lenient().when(userMapper.findAllActive()).thenReturn(List.of());
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

    /* ═══ 편집 가드 ═══════════════════════════════════ */

    @Nested
    @DisplayName("편집 가드")
    class Guards {

        @Test
        @DisplayName("자기 자신을 상위로 지정할 수 없다")
        void cannotParentToSelf() {
            when(gradeMapper.findById(10L)).thenReturn(nodes.get(0));

            assertThatThrownBy(() -> service.updateGrade(10L, "3급", 1, true, 10L, admin()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("자기 자신");
        }

        /*
          자손을 부모로 삼으면 트리가 고리가 되어 순회가 끝나지 않는다 — 화면이 멈춘다.
        */
        @Test
        @DisplayName("자기 하위를 상위로 지정할 수 없다(순환)")
        void cannotParentToOwnDescendant() {
            when(gradeMapper.findById(10L)).thenReturn(nodes.get(0));
            when(gradeMapper.findById(11L)).thenReturn(nodes.get(1));

            assertThatThrownBy(() -> service.updateGrade(10L, "3급", 1, true, 11L, admin()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("순환");
        }

        @Test
        @DisplayName("상위 아래에 하위 직급을 만들 수 있다")
        void childCanBeCreatedUnderParent() {
            when(gradeMapper.findById(10L)).thenReturn(nodes.get(0));
            when(gradeMapper.maxDisplayOrderOfSiblings(10L)).thenReturn(2);

            assertThatCode(() -> service.createGrade("3호봉", null, 10L, admin()))
                    .doesNotThrowAnyException();

            verify(gradeMapper).insert(any());
        }

        @Test
        @DisplayName("이름은 형제 사이에서만 유일하다 — 급마다 '1호봉'을 만들 수 있다")
        void nameIsUniquePerParentOnly() {
            when(gradeMapper.findById(20L)).thenReturn(nodes.get(3));
            when(gradeMapper.findSiblingByName(20L, "1호봉")).thenReturn(null);

            assertThatCode(() -> service.createGrade("1호봉", null, 20L, admin()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("같은 부모 아래 같은 이름은 막는다")
        void duplicateAmongSiblingsIsRejected() {
            when(gradeMapper.findById(10L)).thenReturn(nodes.get(0));
            when(gradeMapper.findSiblingByName(10L, "1호봉")).thenReturn(nodes.get(1));

            assertThatThrownBy(() -> service.createGrade("1호봉", null, 10L, admin()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("이미 있습니다");
        }

        /*
          '3급'을 지우면 그 아래 호봉이 고아가 되거나 FK 로 막힌다. 먼저 정리하라고 알린다.
        */
        @Test
        @DisplayName("하위 직급이 있으면 삭제할 수 없다")
        void cannotDeleteWithChildren() {
            when(gradeMapper.findById(10L)).thenReturn(nodes.get(0));

            assertThatThrownBy(() -> service.deleteGrade(10L, admin()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("하위 직급");

            verify(gradeMapper, never()).delete(any());
        }

        @Test
        @DisplayName("직원이 매달린 말단은 삭제할 수 없다")
        void cannotDeleteWhenUsersAreAttached() {
            when(gradeMapper.findById(11L)).thenReturn(nodes.get(1));
            when(gradeMapper.countUsersByGradeIds(anyList())).thenReturn(2);

            assertThatThrownBy(() -> service.deleteGrade(11L, admin()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("2명");
        }

        @Test
        @DisplayName("비어 있는 말단은 삭제된다")
        void emptyLeafIsDeleted() {
            when(gradeMapper.findById(12L)).thenReturn(nodes.get(2));
            when(gradeMapper.countUsersByGradeIds(anyList())).thenReturn(0);

            service.deleteGrade(12L, admin());

            verify(gradeMapper).delete(12L);
        }
    }

    /* ── 도우미 ──────────────────────────────────────── */

    private EmpGrade node(Long id, Long parentId, String name, int order, int users) {
        EmpGrade g = new EmpGrade();
        g.setId(id);
        g.setParentId(parentId);
        g.setName(name);
        g.setDisplayOrder(order);
        g.setActive(true);
        g.setUserCount(users);
        return g;
    }

    private UserAccount user(Long id, Long gradeId) {
        UserAccount u = new UserAccount();
        u.setId(id);
        u.setGradeId(gradeId);
        u.setName("직원" + id);
        u.setRoles(List.of("EMPLOYEE"));
        return u;
    }

    private CustomUserDetails admin() {
        UserAccount u = new UserAccount();
        u.setId(99L);
        u.setUsername("admin");
        u.setName("관리자");
        u.setRoles(List.of("SYSTEM_ADMIN"));
        return new CustomUserDetails(u);
    }
}
