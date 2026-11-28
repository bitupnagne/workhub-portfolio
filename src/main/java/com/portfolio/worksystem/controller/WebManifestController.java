package com.portfolio.worksystem.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * PWA manifest 를 표준 형식(application/manifest+json)으로 내려 준다.
 * 정적 자원 처리기는 .webmanifest 확장자를 몰라 application/octet-stream 으로 응답한다.
 */
@Controller
public class WebManifestController {

    static final MediaType MANIFEST_JSON = MediaType.parseMediaType("application/manifest+json;charset=UTF-8");

    @GetMapping("/manifest.webmanifest")
    public ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok()
                .contentType(MANIFEST_JSON)
                .body(new ClassPathResource("static/manifest.webmanifest"));
    }
}
