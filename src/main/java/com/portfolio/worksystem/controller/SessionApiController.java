package com.portfolio.worksystem.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionApiController {

    @GetMapping("/api/session/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true);
    }
}
