package com.portfolio.worksystem.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

class WebManifestControllerTest {

    @Test
    void manifestIsServedAsManifestJson() throws Exception {
        ResponseEntity<Resource> response = new WebManifestController().manifest();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(WebManifestController.MANIFEST_JSON));
        assertEquals("manifest+json", response.getHeaders().getContentType().getSubtype());

        Resource body = response.getBody();
        assertNotNull(body);
        assertTrue(body.exists());
        String json = new String(body.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"start_url\""));
    }
}
