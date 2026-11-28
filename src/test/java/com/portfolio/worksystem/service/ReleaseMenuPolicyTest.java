package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.portfolio.worksystem.config.ReleaseFeatures;
import com.portfolio.worksystem.mapper.SystemSettingsMapper;
import com.portfolio.worksystem.model.SystemSetting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReleaseMenuPolicyTest {
    @Test
    void projectPolicyCoversNestedTaskAndEvidenceRoutesWithoutBlockingWeeklyReports() {
        SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
        SystemSettingsService service = new SystemSettingsService(mapper);
        when(mapper.findAll()).thenReturn(List.of(setting("menu.ext.show.project.manage", "false")));
        for (String path : new String[]{"/projects", "/projects/4", "/projects/4/tasks/9", "/projects/4/files/12"}) {
            assertThat(service.isBlockedPath(true, path, null)).as(path).isTrue();
            assertThat(service.isBlockedPath(false, path, null)).as(path).isFalse();
        }
        assertThat(service.isBlockedPath(true, "/report/my", null)).isFalse();
    }

    @Test
    void personnelCardsUseTheExistingPrivateCardPolicyWhileContactsRemainAvailable() {
        SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
        SystemSettingsService service = new SystemSettingsService(mapper);
        when(mapper.findAll()).thenReturn(List.of(setting("menu.ext.show.members.overview", "false")));
        assertThat(service.isBlockedPath(true, "/people/5", null)).isTrue();
        assertThat(service.isBlockedPath(true, "/people/me", null)).isTrue();
        assertThat(service.isBlockedPath(true, "/hr/members/5", null)).isTrue();
        assertThat(service.isBlockedPath(true, "/people", null)).isFalse();
        assertThat(service.isBlockedPath(false, "/people/5", null)).isFalse();
    }

    @Test
    void savingPersonnelEditionPolicyPreservesFutureSettingsAndUpgradeDefaults() {
        SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
        Map<String, String> persisted = new HashMap<>();
        persisted.put("menu.ext.show.dashboard.home", "false");
        when(mapper.findAll()).thenAnswer(call -> persisted.entrySet().stream().map(entry -> setting(entry.getKey(), entry.getValue())).toList());
        doAnswer(call -> { persisted.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(mapper).upsert(anyString(), anyString(), any());
        SystemSettingsService service = new SystemSettingsService(mapper);
        ReflectionTestUtils.setField(service, "releaseFeatures", new ReleaseFeatures("0.1"));
        List<String> checked = service.releaseMenuTree().stream().flatMap(group -> Stream.concat(Stream.of(group.key()), group.children().stream().map(SystemSettingsService.ExtMenuItem::key))).toList();

        service.saveMenuPolicy(checked, checked, null);

        assertThat(persisted.get("menu.ext.show.dashboard.home")).isEqualTo("false");
        for (String key : new String[]{"menu.int.show.hr", "menu.ext.show.hr", "menu.int.show.reports", "menu.ext.show.communication", "menu.int.show.super.releases"}) {
            assertThat(persisted).doesNotContainKey(key);
        }
        ReflectionTestUtils.setField(service, "releaseFeatures", new ReleaseFeatures("0.2"));
        assertThat(service.isBlockedPath(false, "/hr/attendance", null)).isFalse();
        assertThat(service.isBlockedPath(true, "/hr/attendance", null)).isFalse();
        assertThat(service.isBlockedPath(true, "/report/my", null)).isFalse();
    }

    @Test
    void managementDirectoryPolicyDoesNotBlockOwnCardAndOrganizationHomeCannotRedirectLoop() {
        SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
        SystemSettingsService service = new SystemSettingsService(mapper);
        when(mapper.findAll()).thenReturn(List.of(setting("menu.ext.show.org.users", "false"),
                setting("menu.ext.show.hr.orgChart", "false"), setting("menu.ext.show.hr", "false")));
        assertThat(service.isBlockedPath(true, "/people", null)).isTrue();
        assertThat(service.isBlockedPath(true, "/admin/users/new", null)).isTrue();
        assertThat(service.isBlockedPath(true, "/people/me", null)).isFalse();
        assertThat(service.isBlockedPath(true, "/org", null)).isFalse();
        assertThat(service.isBlockedPath(false, "/org", null)).isFalse();
    }

    private SystemSetting setting(String key, String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        return setting;
    }
}
