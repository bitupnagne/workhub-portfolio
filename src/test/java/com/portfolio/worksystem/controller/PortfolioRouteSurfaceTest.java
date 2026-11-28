package com.portfolio.worksystem.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/** 보안 필터와 별개로 삭제한 기능의 MVC 핸들러가 빌드에 남지 않았는지 확인한다. */
class PortfolioRouteSurfaceTest {
    @Test
    void registeredControllersKeepCoreWorkflowsAndOmitRetiredHandlers() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> paths = new ArrayList<>();
        for (var bean : scanner.findCandidateComponents("com.portfolio.worksystem.controller")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] bases = classMapping == null || classMapping.path().length == 0
                    ? new String[]{""} : classMapping.path();
            for (var method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (String base : bases) {
                    Arrays.stream(mapping.path()).map(path -> base + path).forEach(paths::add);
                }
            }
        }
        assertThat(paths).contains("/report/my", "/hr/attendance", "/projects", "/admin/grades", "/admin/personnel/transfer",
                "/admin/users", "/api/desktop/login", "/api/desktop/otp/state");
        assertThat(paths).noneMatch(path -> path.matches(
                "^/(eval|admin/eval|hr/performance|api/desktop/eval)(/.*)?$"));
        assertThat(paths).doesNotContain("/admin/org/kpi-target");
    }
}
