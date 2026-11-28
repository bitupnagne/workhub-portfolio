package com.portfolio.worksystem.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Stable first-page URL for editions before the dashboard is introduced. */
@Controller
public class MyWorkController {
    @GetMapping("/my-work")
    public String myWork() {
        return "redirect:/hr/attendance";
    }
}
