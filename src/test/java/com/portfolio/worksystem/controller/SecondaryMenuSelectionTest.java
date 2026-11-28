package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

class SecondaryMenuSelectionTest {
    @Test
    void integratedAttendanceApplicationsSelectTheirExistingParentLeaf() throws Exception {
        for (String active : Set.of("attendance", "leave", "leaveNew", "flexTime", "flexTimeNew", "trips", "tripNew")) {
            assertThat(renderAttendanceLink(active)).as(active).contains("class=\"active\"");
        }
    }

    @Test
    void otherHrLeavesDoNotAlsoSelectAttendance() throws Exception {
        for (String active : Set.of("loa", "loaNew", "schedules", "orgChart", "documentsIo")) {
            assertThat(renderAttendanceLink(active)).as(active).doesNotContain("class=\"active\"");
        }
    }

    private String renderAttendanceLink(String active) throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/templates/fragments/app.html")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String link = source.lines().filter(line -> line.contains("th:href=\"@{/hr/attendance}\""))
                .findFirst().orElseThrow().replace("th:href=\"@{/hr/attendance}\"", "href=\"/hr/attendance\"");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        return engine.process(link, new Context(Locale.KOREAN, Map.of("active", active, "hiddenMenus", Set.of())));
    }
}
