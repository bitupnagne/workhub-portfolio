package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.service.AccountService;
import com.portfolio.worksystem.service.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.io.IOException;
import java.time.Duration;

@RequiredArgsConstructor
@Controller
public class ProfileImageController {
    private final AccountService accountService;

    // [B-6] ETag + maxAge 캐싱 — 동일 파일에 대해 If-None-Match 매칭 시 304(Not Modified) 반환.
    // 브라우저/WPF HttpClient가 자동으로 재사용 → 같은 사진 반복 다운로드 트래픽 제거.

    private ResponseEntity<Resource> serve(Resource resource,
                                           org.springframework.http.MediaType mediaType,
                                           Duration maxAge,
                                           String ifNoneMatch) {
        String etag = computeEtag(resource);
        if (etag != null && etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(maxAge).cachePublic())
                    .build();
        }
        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(maxAge).cachePublic())
                .contentType(mediaType);
        if (etag != null) b = b.eTag(etag);
        return b.body(resource);
    }

    private String computeEtag(Resource resource) {
        try {
            long lastMod = resource.lastModified();
            long len = resource.contentLength();
            if (lastMod <= 0) return null;
            return "\"" + Long.toHexString(lastMod) + "-" + Long.toHexString(len) + "\"";
        } catch (IOException e) {
            return null;
        }
    }

    @GetMapping("/profile-images/{filename:.+}")
    public ResponseEntity<Resource> profileImage(@PathVariable String filename,
                                                 @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        try {
            AccountService.StoredProfileImage image = accountService.loadProfileImage(filename);
            return serve(image.resource(), image.mediaType(), Duration.ofDays(7), ifNoneMatch);
        } catch (BusinessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/signature-images/{filename:.+}")
    public ResponseEntity<Resource> signatureImage(@PathVariable String filename,
                                                   @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        try {
            AccountService.StoredProfileImage image = accountService.loadSignature(filename);
            return serve(image.resource(), image.mediaType(), Duration.ofDays(7), ifNoneMatch);
        } catch (BusinessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/notice-images/{filename:.+}")
    public ResponseEntity<Resource> noticeImage(@PathVariable String filename,
                                                @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        try {
            AccountService.StoredProfileImage image = accountService.loadNoticeImage(filename);
            return serve(image.resource(), image.mediaType(), Duration.ofDays(7), ifNoneMatch);
        } catch (BusinessException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
