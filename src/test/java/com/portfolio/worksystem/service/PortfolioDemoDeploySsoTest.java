package com.portfolio.worksystem.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.portfolio.worksystem.mapper.DeploySsoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class PortfolioDemoDeploySsoTest {
    @Test void publicDemoRejectsIntegrationBeforeDatabaseAccessOrHttpRequests() {
        var mapper = mock(DeploySsoMapper.class);
        var settings = mock(SystemSettingsService.class);
        var json = mock(ObjectMapper.class);
        var service = new DeploySsoService(mapper, settings, json);
        ReflectionTestUtils.setField(service, "publicDemo", true);
        assertThrows(BusinessException.class, () -> service.link(null, "demo-code"));
        assertThrows(BusinessException.class, () -> service.unlink(null));
        assertThrows(BusinessException.class, () -> service.issueLoginUrl(null));
        verifyNoInteractions(mapper, settings, json);
    }
}
