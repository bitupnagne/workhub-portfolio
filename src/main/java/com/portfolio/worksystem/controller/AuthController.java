package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.service.ReportService;
import com.portfolio.worksystem.service.WeekUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@RequiredArgsConstructor
@Controller
public class AuthController {
    private final com.portfolio.worksystem.config.ReleaseFeatures releaseFeatures;
    private final ReportService reportService;

    @org.springframework.beans.factory.annotation.Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}")
    private boolean publicDemo;

    @org.springframework.beans.factory.annotation.Value("${app.portfolio.demo-password:${PORTFOLIO_DEMO_PASSWORD:}}")
    private String demoPassword;

    @GetMapping("/login")
    public String login(Authentication authentication, Model model) {
        if (isLoggedIn(authentication)) {
            return "redirect:" + releaseFeatures.getHomeUrl();
        }
        String weekKey = reportService.defaultWeekKey();
        model.addAttribute("weekLabel", WeekUtils.getWeekLabel(weekKey));
        model.addAttribute("weekPeriod", WeekUtils.getWeekPeriod(weekKey));
        model.addAttribute("publicDemo", publicDemo);
        if (publicDemo) model.addAttribute("publicDemoPassword", demoPassword);
        return "auth/login";
    }

    @GetMapping("/demo-restricted")
    public String demoRestricted(Model model) {
        model.addAttribute("demoRestrictionMessage", com.portfolio.worksystem.config.PortfolioPublicDemoPolicy.MESSAGE);
        return "error/demo-restricted";
    }

    @GetMapping("/")
    public String home(Authentication authentication) {
        if (!isLoggedIn(authentication)) {
            return "redirect:/login";
        }
        return "redirect:" + releaseFeatures.getHomeUrl();
    }

    private boolean isLoggedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
