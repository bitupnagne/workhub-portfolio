package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.model.WorkMemo;
import com.portfolio.worksystem.model.WorkMemoAttachment;
import com.portfolio.worksystem.model.WorkMemoRecipient;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.WorkMemoService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class WorkMemoApiController {

    private final WorkMemoService workMemoService;

    @GetMapping("/api/work-memos")
    public Map<String, Object> list(@AuthenticationPrincipal CustomUserDetails user,
                                    @RequestParam(value = "box", defaultValue = "received") String box,
                                    @RequestParam(value = "q", required = false) String q,
                                    @RequestParam(value = "startDate", required = false) String startDate,
                                    @RequestParam(value = "endDate", required = false) String endDate,
                                    @RequestParam(value = "page", defaultValue = "0") int page) {
        List<WorkMemo> items;
        int total;
        switch (box) {
            case "sent":
                items = workMemoService.sent(user, q, startDate, endDate, page);
                total = workMemoService.countSent(user, q, startDate, endDate);
                break;
            case "starred":
            case "important":
                items = workMemoService.starred(user, q, startDate, endDate, page);
                total = workMemoService.countStarred(user, q, startDate, endDate);
                break;
            default:
                items = workMemoService.received(user, q, startDate, endDate, page);
                total = workMemoService.countReceived(user, q, startDate, endDate);
        }
        return Map.of(
                "box", box,
                "items", items,
                "total", total,
                "unreadReceived", workMemoService.countUnreadReceived(user)
        );
    }

    @GetMapping("/api/work-memos/unread-count")
    public Map<String, Integer> unread(@AuthenticationPrincipal CustomUserDetails user) {
        return Map.of("count", workMemoService.countUnreadReceived(user));
    }

    @GetMapping("/api/work-memos/{memoId}")
    public Map<String, Object> detail(@AuthenticationPrincipal CustomUserDetails user,
                                      @PathVariable Long memoId,
                                      @RequestParam(value = "markRead", defaultValue = "true") boolean markRead) {
        WorkMemo memo = workMemoService.detail(user, memoId, markRead);
        List<WorkMemoRecipient> recipients = workMemoService.recipients(user, memoId);
        return Map.of("memo", memo, "recipients", recipients);
    }

    @PostMapping(value = "/api/work-memos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> create(@AuthenticationPrincipal CustomUserDetails user,
                                      @RequestParam("title") String title,
                                      @RequestParam(value = "body", required = false) String body,
                                      @RequestParam("recipientIds") List<Long> recipientIds,
                                      @RequestParam(value = "importance", defaultValue = "NORMAL") String importance,
                                      @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        Long id = workMemoService.create(user, title, body, recipientIds, importance, files);
        return Map.of("id", id);
    }

    @PostMapping("/api/work-memos/{memoId}/read")
    public Map<String, Boolean> markRead(@AuthenticationPrincipal CustomUserDetails user,
                                         @PathVariable Long memoId) {
        workMemoService.markRead(user, memoId);
        return Map.of("ok", true);
    }

    @PostMapping("/api/work-memos/{memoId}/star")
    public Map<String, Boolean> toggleStar(@AuthenticationPrincipal CustomUserDetails user,
                                           @PathVariable Long memoId) {
        boolean now = workMemoService.toggleStar(user, memoId);
        return Map.of("starred", now);
    }

    @GetMapping("/api/work-memos/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(@AuthenticationPrincipal CustomUserDetails user,
                                                       @PathVariable Long attachmentId) {
        WorkMemoAttachment att = workMemoService.attachment(user, attachmentId);
        Resource resource = workMemoService.attachmentResource(user, attachmentId);
        boolean inline = "IMAGE".equalsIgnoreCase(att.getAttachmentType());
        ContentDisposition disp = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(att.getOriginalFilename(), StandardCharsets.UTF_8).build();
        MediaType ct = att.getContentType() != null && !att.getContentType().isBlank()
                ? MediaType.parseMediaType(att.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(ct)
                .contentLength(att.getFileSize())
                .header("Content-Disposition", disp.toString())
                .header("Cache-Control", inline ? "private, max-age=604800" : "private, no-cache")
                .body(resource);
    }
}
