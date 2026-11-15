package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.MessageForm;
import com.portfolio.worksystem.dto.MessageInviteForm;
import com.portfolio.worksystem.dto.MessageRoomForm;
import com.portfolio.worksystem.dto.MessageRoomSettingsForm;
import com.portfolio.worksystem.dto.MessagePagePayload;
import com.portfolio.worksystem.dto.MessageStreamPayload;
import com.portfolio.worksystem.dto.RightPanelPayload;
import com.portfolio.worksystem.model.MessageAttachment;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.CommunicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RequiredArgsConstructor
@RestController
public class CommunicationApiController {
    private final CommunicationService communicationService;

    @GetMapping("/api/right-panel")
    public RightPanelPayload rightPanel(@AuthenticationPrincipal CustomUserDetails user) {
        return communicationService.rightPanel(user);
    }

    @GetMapping("/api/messages/{roomId}")
    public MessageStreamPayload messages(@AuthenticationPrincipal CustomUserDetails user,
                                         @PathVariable Long roomId,
                                         @RequestParam(value = "limit", required = false) Integer limit) {
        return messagePayload(user, roomId, limit);
    }

    /** 위로 스크롤 시 과거 메시지 페이지(opt-in). */
    @GetMapping("/api/messages/{roomId}/before")
    public MessagePagePayload messagesBefore(@AuthenticationPrincipal CustomUserDetails user,
                                             @PathVariable Long roomId,
                                             @RequestParam("beforeId") Long beforeId,
                                             @RequestParam(value = "limit", required = false) Integer limit) {
        int lim = (limit == null || limit <= 0) ? 80 : limit;
        var older = communicationService.messagesBefore(user, roomId, beforeId, lim);
        boolean hasMore = !older.isEmpty() && communicationService.hasMessagesBefore(user, roomId, older.get(0).getId());
        return new MessagePagePayload(older, hasMore);
    }

    @PostMapping("/api/messages/{roomId}")
    public MessageStreamPayload sendMessage(@AuthenticationPrincipal CustomUserDetails user,
                                            @PathVariable Long roomId,
                                            @RequestParam(value = "limit", required = false) Integer limit,
                                            @Valid @RequestBody MessageForm form) {
        communicationService.sendMessage(user, roomId, form);
        return messagePayload(user, roomId, limit);
    }

    @PostMapping(value = "/api/messages/{roomId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageStreamPayload sendAttachments(@AuthenticationPrincipal CustomUserDetails user,
                                                @PathVariable Long roomId,
                                                @RequestParam(value = "body", required = false) String body,
                                                @RequestParam(value = "limit", required = false) Integer limit,
                                                @RequestParam("files") List<MultipartFile> files) {
        communicationService.sendMessageAttachments(user, roomId, body, files);
        return messagePayload(user, roomId, limit);
    }

    @GetMapping("/api/messages/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(@AuthenticationPrincipal CustomUserDetails user,
                                                       @PathVariable Long attachmentId,
                                                       @RequestParam(value = "w", required = false) Integer w) {
        MessageAttachment attachment = communicationService.messageAttachment(user, attachmentId);
        boolean isImage = attachment.getAttachmentType() != null
                && attachment.getAttachmentType().equalsIgnoreCase("IMAGE");

        // 그리드 썸네일 요청(?w=): 이미지면 축소 JPEG 제공(+장기 캐시). 비이미지/실패면 원본 폴백.
        if (w != null && isImage) {
            Resource thumb = communicationService.messageImageThumbnail(user, attachmentId, w);
            // 축소 실패 폴백(SVG 등 ImageIO 미지원)이면 원본 Content-Type 으로 —
            // JPEG 로 잘못 선언하면 브라우저가 디코딩 실패해 깨진 타일로 그린다.
            boolean fallback = thumb.getFilename() != null
                    && thumb.getFilename().equals(attachment.getStoredFilename());
            return ResponseEntity.ok()
                    .contentType(fallback ? MediaType.parseMediaType(attachment.getContentType()) : MediaType.IMAGE_JPEG)
                    .header("Cache-Control", "private, max-age=604800")
                    .header("Content-Disposition", ContentDisposition.inline().build().toString())
                    .body(thumb);
        }

        Resource resource = communicationService.messageAttachmentResource(user, attachmentId);
        ContentDisposition disposition = isImage
                ? ContentDisposition.inline().filename(attachment.getOriginalFilename(), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(attachment.getOriginalFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .contentLength(attachment.getFileSize())
                .header("Cache-Control", isImage ? "private, max-age=604800" : "private, no-cache")
                .header("Content-Disposition", disposition.toString())
                .body(resource);
    }

    @PostMapping("/api/messages/direct/{recipientUserId}")
    public MessageStreamPayload directRoom(@AuthenticationPrincipal CustomUserDetails user,
                                           @PathVariable Long recipientUserId) {
        Long roomId = communicationService.openDirectRoom(user, recipientUserId);
        return messagePayload(user, roomId, null);
    }

    @PostMapping("/api/messages/rooms")
    public MessageStreamPayload createRoom(@AuthenticationPrincipal CustomUserDetails user,
                                           @Valid @RequestBody MessageRoomForm form) {
        Long roomId = communicationService.createRoom(user, form);
        return messagePayload(user, roomId, null);
    }

    @PostMapping("/api/messages/{roomId}/invite")
    public MessageStreamPayload invite(@AuthenticationPrincipal CustomUserDetails user,
                                       @PathVariable Long roomId,
                                       @Valid @RequestBody MessageInviteForm form) {
        communicationService.inviteMembers(user, roomId, form.userIds());
        return messagePayload(user, roomId, null);
    }

    @PostMapping("/api/messages/{roomId}/leave")
    public RightPanelPayload leaveRoom(@AuthenticationPrincipal CustomUserDetails user,
                                       @PathVariable Long roomId) {
        communicationService.leaveRoom(user, roomId);
        return communicationService.rightPanel(user);
    }

    @PostMapping("/api/messages/{roomId}/settings")
    public com.portfolio.worksystem.model.MessageRoom updateRoomSettings(@AuthenticationPrincipal CustomUserDetails user,
                                                                      @PathVariable Long roomId,
                                                                      @RequestBody MessageRoomSettingsForm form) {
        return communicationService.updateRoomSettings(user, roomId, form);
    }

    @PostMapping("/api/notifications/{notificationId}/read")
    public RightPanelPayload markNotificationRead(@AuthenticationPrincipal CustomUserDetails user,
                                                  @PathVariable Long notificationId) {
        communicationService.markNotificationRead(user, notificationId);
        return communicationService.rightPanel(user);
    }

    /** [W9] 알림 모두 읽음 — 웹 우측 패널·윈도우 앱 공용. */
    @PostMapping("/api/notifications/read-all")
    public RightPanelPayload markAllNotificationsRead(@AuthenticationPrincipal CustomUserDetails user) {
        communicationService.markAllNotificationsRead(user);
        return communicationService.rightPanel(user);
    }

    @PostMapping("/api/notifications/{notificationId}/delete")
    public RightPanelPayload deleteNotification(@AuthenticationPrincipal CustomUserDetails user,
                                                @PathVariable Long notificationId) {
        communicationService.deleteNotification(user, notificationId);
        return communicationService.rightPanel(user);
    }

    @PostMapping("/api/messages/{roomId}/read-notifications")
    public RightPanelPayload markRoomNotificationsRead(@AuthenticationPrincipal CustomUserDetails user,
                                                       @PathVariable Long roomId) {
        communicationService.markMessageRoomNotificationsRead(user, roomId);
        return communicationService.rightPanel(user);
    }

    /** [S-3] 초대 후보를 별도 endpoint로 분리 — 모달 열릴 때만 호출되어 메시지 polling 응답 크기 감소. */
    @GetMapping("/api/messages/{roomId}/invite-candidates")
    public java.util.List<com.portfolio.worksystem.model.UserAccount> inviteCandidates(@AuthenticationPrincipal CustomUserDetails user,
                                                                                   @PathVariable Long roomId) {
        return communicationService.inviteCandidates(user, roomId);
    }

    private MessageStreamPayload messagePayload(CustomUserDetails user, Long roomId, Integer limit) {
        var msgs = (limit != null && limit > 0)
                ? communicationService.recentMessages(user, roomId, limit)
                : communicationService.messages(user, roomId);
        boolean hasMore = (limit != null && limit > 0) && !msgs.isEmpty()
                && communicationService.hasMessagesBefore(user, roomId, msgs.get(0).getId());
        return new MessageStreamPayload(
                user.getUserId(),
                communicationService.unreadMessageCount(user),
                communicationService.room(user, roomId),
                communicationService.members(user, roomId),
                // [S-3] 매 메시지 응답마다 전체 직원 목록을 보내지 않는다. 모달 열 때 별도 fetch.
                java.util.List.of(),
                msgs,
                hasMore
        );
    }
}
