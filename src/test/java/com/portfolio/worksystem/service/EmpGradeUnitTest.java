package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** [GRADE] 직급 = 값 + 단위. 값·단위 모두 자유 텍스트이고, 단위는 단계별 기본값을 따르거나 노드에서 바꾼다. */
class EmpGradeUnitTest {
    private final EmpGradeMapper mapper = mock(EmpGradeMapper.class);
    private final EmpGradeService service = new EmpGradeService(mapper, mock(UserMapper.class), mock(TeamMapper.class));
    private final List<EmpGrade> nodes = new ArrayList<>();
    private final List<Map<String, Object>> units = new ArrayList<>();

    @BeforeEach
    void setUp() {
        nodes.add(node(10L, null, "3", null, "3급"));
        nodes.add(node(11L, 10L, "1", null, "1호봉"));
        when(mapper.findAllWithUserCount()).thenReturn(nodes);
        when(mapper.findDepthUnits()).thenReturn(units);
        when(mapper.findById(10L)).thenReturn(nodes.get(0));
        when(mapper.findById(11L)).thenReturn(nodes.get(1));
    }

    @Test
    void valueAndDepthUnitMakeTheName() {
        units.add(Map.of("depth", 0, "unit_label", "급"));
        units.add(Map.of("depth", 1, "unit_label", "호봉"));

        service.createGrade("2", null, null, 10L, admin());

        ArgumentCaptor<EmpGrade> saved = ArgumentCaptor.forClass(EmpGrade.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("2호봉");
        assertThat(saved.getValue().getGradeValue()).isEqualTo("2");
        assertThat(saved.getValue().getUnitLabel()).isNull();
    }

    @Test
    void textValuesAndNodeUnitsAreAllowed() {
        units.add(Map.of("depth", 0, "unit_label", "급"));

        // 단위 없음('')이면 값만, 직접 지정하면 그 단위
        service.createGrade("선임", "", null, null, admin());
        service.createGrade("A", "직급", null, null, admin());

        ArgumentCaptor<EmpGrade> saved = ArgumentCaptor.forClass(EmpGrade.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(saved.capture());
        assertThat(saved.getAllValues()).extracting(EmpGrade::getName).containsExactly("선임", "A직급");
    }

    @Test
    void changingADepthUnitRenamesThatDepthOnly() {
        units.add(Map.of("depth", 1, "unit_label", "단계"));
        when(mapper.findDepthUnits()).thenReturn(units);

        service.saveDepthUnits(Map.of(1, "단계"), admin());

        verify(mapper).upsertDepthUnit(eq(1), eq("단계"), any());
        verify(mapper).updateName(11L, "1단계");
        // 최상위는 단계 단위가 없어 값만 — '3급'에서 '3'으로 바뀐다(단위를 비워 둔 상태).
        verify(mapper).updateName(10L, "3");
    }

    @Test
    void siblingsCannotEndUpWithTheSameName() {
        nodes.add(node(12L, 10L, "1", "", "1"));
        units.add(Map.of("depth", 1, "unit_label", "호봉"));

        // 11번은 '1'+'호봉', 12번은 '1'+'' → 서로 다르다. 단위를 비우면 둘 다 '1'이 되어 막힌다.
        units.clear();
        assertThatThrownBy(() -> service.saveDepthUnits(Map.of(1, ""), admin()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("두 개");
        verify(mapper, never()).updateName(eq(12L), any());
    }

    @Test
    void movingSwapsWithTheNeighbourAndRenumbers() {
        nodes.add(node(12L, 10L, "2", null, "2호봉"));
        nodes.get(1).setDisplayOrder(1);
        nodes.get(2).setDisplayOrder(2);
        when(mapper.findById(12L)).thenReturn(nodes.get(2));

        service.moveGrade(12L, true, admin());

        verify(mapper).updateDisplayOrder(12L, 1);
        verify(mapper).updateDisplayOrder(11L, 2);
        verify(mapper, never()).updateDisplayOrder(eq(10L), anyInt());
    }

    private static EmpGrade node(Long id, Long parent, String value, String unit, String name) {
        EmpGrade grade = new EmpGrade();
        grade.setId(id);
        grade.setParentId(parent);
        grade.setGradeValue(value);
        grade.setUnitLabel(unit);
        grade.setName(name);
        grade.setActive(true);
        grade.setDisplayOrder(id.intValue());
        grade.setUserCount(0);
        return grade;
    }

    private static CustomUserDetails admin() {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setUsername("hr");
        account.setName("인사");
        account.setRoles(List.of("HR_ADMIN"));
        return new CustomUserDetails(account);
    }
}
