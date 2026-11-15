package com.portfolio.worksystem.service;

import com.portfolio.worksystem.mapper.WorkMemoMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.websocket.RealtimeHub;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.model.WorkMemo;
import com.portfolio.worksystem.model.WorkMemoAttachment;
import com.portfolio.worksystem.model.WorkMemoRecipient;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class WorkMemoService {

    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 100L * 1024L * 1024L;
    private static final int DEFAULT_PAGE_SIZE = 30;

    private final WorkMemoMapper workMemoMapper;
    private final UserMapper userMapper;
    private final RealtimeHub realtimeHub;
    private final CommunicationService communicationService;
    private final SystemSettingsService systemSettingsService;

    @Value("${app.upload.message-attachment-dir:/opt/worksystem/message-attachments}")
    private String baseAttachmentDir;

    // ----- 목록 -----
    public List<WorkMemo> received(CustomUserDetails user, String query, String startDate, String endDate, int page) {
        int limit = DEFAULT_PAGE_SIZE;
        int offset = Math.max(0, page) * limit;
        var list = workMemoMapper.findReceivedMemos(user.getUserId(), nullIfBlank(query), nullIfBlank(startDate), nullIfBlank(endDate), limit, offset);
        attachAttachments(list);
        return list;
    }

    public List<WorkMemo> sent(CustomUserDetails user, String query, String startDate, String endDate, int page) {
        int limit = DEFAULT_PAGE_SIZE;
        int offset = Math.max(0, page) * limit;
        var list = workMemoMapper.findSentMemos(user.getUserId(), nullIfBlank(query), nullIfBlank(startDate), nullIfBlank(endDate), limit, offset);
        attachAttachments(list);
        return list;
    }

    public List<WorkMemo> starred(CustomUserDetails user, String query, String startDate, String endDate, int page) {
        int limit = DEFAULT_PAGE_SIZE;
        int offset = Math.max(0, page) * limit;
        var list = workMemoMapper.findStarredMemos(user.getUserId(), nullIfBlank(query), nullIfBlank(startDate), nullIfBlank(endDate), limit, offset);
        attachAttachments(list);
        return list;
    }

    public int countReceived(CustomUserDetails user, String query, String startDate, String endDate) {
        return workMemoMapper.countReceived(user.getUserId(), nullIfBlank(query), nullIfBlank(startDate), nullIfBlank(endDate));
    }

    public int countSent(CustomUserDetails user, String query, String startDate, String endDate) {
        return workMemoMapper.countSent(user.getUserId(), nullIfBlank(query), nullIfBlank(startDate), nullIfBlank(endDate));
    }

    public int countStarred(CustomUserDetails user, String query, String startDate, String endDate) {
        return workMemoMapper.countStarred(user.getUserId(), nullIfBlank(query), nullIfBlank(startDate), nullIfBlank(endDate));
    }

    public int countUnreadReceived(CustomUserDetails user) {
        return workMemoMapper.countUnreadReceived(user.getUserId());
    }

    // ----- 상세 -----
    @Transactional
    public WorkMemo detail(CustomUserDetails user, Long memoId, boolean markRead) {
        WorkMemo memo = workMemoMapper.findMemo(memoId, user.getUserId());
        if (memo == null) {
            throw new BusinessException("자료전송을 찾을 수 없습니다.");
        }
        memo.setAttachments(workMemoMapper.findAttachments(memoId));
        // 받은함 항목이면 자동 읽음 처리 — 연동된 알림도 같이 정리해서 헤더 종 뱃지 갱신.
        if (markRead && memo.getReadAt() == null && !memo.getSenderUserId().equals(user.getUserId())) {
            workMemoMapper.markRead(memoId, user.getUserId());
            memo.setReadAt(LocalDateTime.now());
        }
        if (markRead) {
            try { communicationService.markWorkMemoNotificationsRead(user, memoId); }
            catch (Exception ignored) {}
        }
        return memo;
    }

    public List<WorkMemoRecipient> recipients(CustomUserDetails user, Long memoId) {
        return workMemoMapper.findRecipients(memoId);
    }

    // ----- 작성 -----
    @Transactional
    public Long create(CustomUserDetails user, String title, String body, List<Long> recipientIds,
                       String importance, List<MultipartFile> attachments) {
        if (!StringUtils.hasText(title)) {
            throw new BusinessException("제목을 입력하세요.");
        }
        if (recipientIds == null || recipientIds.isEmpty()) {
            throw new BusinessException("수신자를 한 명 이상 선택하세요.");
        }
        List<MultipartFile> files = attachments == null ? List.of()
                : attachments.stream().filter(f -> f != null && !f.isEmpty()).toList();
        long totalSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        if (totalSize > MAX_TOTAL_ATTACHMENT_BYTES) {
            throw new BusinessException("첨부파일은 총 100MB까지 업로드할 수 있습니다.");
        }
        for (MultipartFile file : files) {
            systemSettingsService.validateUploadExtension(file.getOriginalFilename());
        }

        String norm = "IMPORTANT".equalsIgnoreCase(importance) ? "IMPORTANT" : "NORMAL";

        WorkMemo memo = new WorkMemo();
        memo.setSenderUserId(user.getUserId());
        memo.setTitle(title.trim());
        memo.setBody(body != null ? body : "");
        memo.setImportance(norm);
        memo.setSentAt(LocalDateTime.now());
        workMemoMapper.insertMemo(memo);

        for (Long recipientId : recipientIds.stream().distinct().toList()) {
            UserAccount u = userMapper.findById(recipientId);
            if (u == null || !Boolean.TRUE.equals(u.getActive())) continue;
            WorkMemoRecipient r = new WorkMemoRecipient();
            r.setMemoId(memo.getId());
            r.setRecipientUserId(recipientId);
            r.setStarred(false);
            workMemoMapper.insertRecipient(r);
        }

        for (MultipartFile file : files) {
            storeAttachment(memo, file);
        }
        // 알림 행 생성 — 수신자에게 헤더 종 팝오버에 떠야 함.
        // [1.0.78] 중요/IMPORTANT 개념 폐기 — 별표(즐겨찾기) 로 통합. prefix 제거.
        UserAccount sender = userMapper.findById(user.getUserId());
        String senderName = sender != null && sender.getName() != null ? sender.getName() : user.getUsername();
        String notifTitle = senderName + "님의 자료전송";
        String notifBody = title.trim();
        String linkUrl = "/work-memos/" + memo.getId();
        for (Long recipientId : recipientIds.stream().distinct().toList()) {
            try {
                communicationService.notifyUser(recipientId, user.getUserId(), "WORK_MEMO", notifTitle, notifBody, linkUrl);
            } catch (Exception ignored) {}
        }
        // 실시간 패널 갱신 — 작성자 본인도 발신함 갱신용
        java.util.Set<Long> notifyTargets = new java.util.HashSet<>(recipientIds);
        notifyTargets.add(user.getUserId());
        try {
            realtimeHub.sendToUsers(notifyTargets, "PANEL_CHANGED", java.util.Map.of("reason", "work-memo"));
        } catch (Exception ignored) {}
        return memo.getId();
    }

    // ----- 상태 -----
    public void markRead(CustomUserDetails user, Long memoId) {
        workMemoMapper.markRead(memoId, user.getUserId());
    }

    public boolean toggleStar(CustomUserDetails user, Long memoId) {
        WorkMemo memo = workMemoMapper.findMemo(memoId, user.getUserId());
        if (memo == null) throw new BusinessException("자료전송을 찾을 수 없습니다.");
        boolean next = !memo.isStarred();
        int updated = workMemoMapper.setStarred(memoId, user.getUserId(), next);
        // 발신자(본인 발송한 메모)에는 recipient 행이 없을 수 있다. 그 경우 self-recipient 행을 만들어 별표 보관.
        if (updated == 0) {
            WorkMemoRecipient r = new WorkMemoRecipient();
            r.setMemoId(memoId);
            r.setRecipientUserId(user.getUserId());
            r.setStarred(next);
            r.setReadAt(java.time.LocalDateTime.now());
            try { workMemoMapper.insertRecipient(r); }
            catch (Exception ignored) {
                // unique 충돌이면 다시 update
                workMemoMapper.setStarred(memoId, user.getUserId(), next);
            }
        }
        return next;
    }

    // ----- 첨부 다운로드 -----
    public WorkMemoAttachment attachment(CustomUserDetails user, Long attachmentId) {
        WorkMemoAttachment att = workMemoMapper.findAttachment(attachmentId);
        if (att == null) throw new BusinessException("첨부파일을 찾을 수 없습니다.");
        // 권한: 발신자거나 수신자여야 함
        WorkMemo memo = workMemoMapper.findMemo(att.getMemoId(), user.getUserId());
        if (memo == null) throw new BusinessException("접근 권한이 없습니다.");
        return att;
    }

    public Resource attachmentResource(CustomUserDetails user, Long attachmentId) {
        WorkMemoAttachment att = attachment(user, attachmentId);
        Path file = attachmentRoot().resolve(att.getStoredFilename()).normalize();
        if (!file.startsWith(attachmentRoot()) || !Files.isRegularFile(file)) {
            throw new BusinessException("첨부파일을 읽을 수 없습니다.");
        }
        try {
            return new UrlResource(file.toUri());
        } catch (IOException e) {
            throw new BusinessException("첨부파일을 읽을 수 없습니다.");
        }
    }

    // ----- 내부 -----
    private void storeAttachment(WorkMemo memo, MultipartFile file) {
        try {
            Path root = attachmentRoot();
            Files.createDirectories(root);
            String original = cleanFilename(file.getOriginalFilename());
            String ext = extensionOf(original);
            String stored = "wm_" + memo.getId() + "_" + UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = root.resolve(stored).normalize();
            if (!target.startsWith(root)) throw new BusinessException("잘못된 파일명입니다.");
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "업무메모 첨부");
            WorkMemoAttachment att = new WorkMemoAttachment();
            att.setMemoId(memo.getId());
            att.setOriginalFilename(original);
            att.setStoredFilename(stored);
            att.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
            att.setFileSize(file.getSize());
            att.setAttachmentType(detectType(file.getContentType(), original));
            workMemoMapper.insertAttachment(att);
        } catch (IOException e) {
            throw new BusinessException("첨부파일 저장에 실패했습니다.");
        }
    }

    private void attachAttachments(List<WorkMemo> memos) {
        for (WorkMemo m : memos) {
            m.setAttachments(workMemoMapper.findAttachments(m.getId()));
        }
    }

    private Path attachmentRoot() {
        return Path.of(baseAttachmentDir).toAbsolutePath().resolve("work-memos").normalize();
    }

    private static String cleanFilename(String name) {
        if (!StringUtils.hasText(name)) return "attachment";
        String only = Path.of(name).getFileName().toString().replaceAll("[\\r\\n\\\\/]+", "_").trim();
        return only.isBlank() ? "attachment" : only;
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot).toLowerCase();
        return ext.matches("\\.[a-z0-9]{1,10}") ? ext : "";
    }

    private static String detectType(String contentType, String filename) {
        String c = contentType == null ? "" : contentType.toLowerCase();
        String n = filename == null ? "" : filename.toLowerCase();
        if (c.startsWith("image/") || n.matches(".*\\.(png|jpg|jpeg|gif|webp|bmp)$")) return "IMAGE";
        return "FILE";
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
