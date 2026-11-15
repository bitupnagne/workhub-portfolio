package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.PasswordChangeForm;
import com.portfolio.worksystem.dto.ProfileInfoForm;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.util.AfterCommit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@Service
@lombok.extern.slf4j.Slf4j
public class AccountService {
    private static final long MAX_PROFILE_IMAGE_BYTES = 2L * 1024L * 1024L;
    private static final long MAX_SIGNATURE_BYTES = 1L * 1024L * 1024L;
    private static final long MAX_NOTICE_IMAGE_BYTES = 5L * 1024L * 1024L;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PanelRefreshPublisher panelRefreshPublisher;
    /** [B9] 인사정보 변경이력. */
    private final UserChangeLogService userChangeLogService;
    private final PortfolioStorageBudget storageBudget;

    @Value("${app.upload.profile-dir:uploads/profile-images}")
    private String profileImageDir;

    @Value("${app.upload.signature-dir:uploads/signatures}")
    private String signatureDir;

    @Value("${app.upload.notice-dir:uploads/notice-images}")
    private String noticeImageDir;

    @Transactional(readOnly = true)
    public boolean matchesPassword(Long userId, String rawPassword) {
        if (userId == null || rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        UserAccount user = userMapper.findById(userId);
        return user != null && passwordEncoder.matches(rawPassword, user.getPassword());
    }

    @Transactional
    public void changePassword(CustomUserDetails currentUser, PasswordChangeForm form) {
        UserAccount user = userMapper.findById(currentUser.getUserId());
        if (user == null) {
            throw new BusinessException("사용자를 찾을 수 없습니다.");
        }
        if (!passwordEncoder.matches(form.currentPassword(), user.getPassword())) {
            throw new BusinessException("현재 비밀번호가 올바르지 않습니다.");
        }
        if (!form.newPassword().equals(form.confirmPassword())) {
            throw new BusinessException("새 비밀번호와 확인 값이 일치하지 않습니다.");
        }
        if (passwordEncoder.matches(form.newPassword(), user.getPassword())) {
            throw new BusinessException("현재 비밀번호와 다른 비밀번호를 사용하세요.");
        }
        try {
            com.portfolio.worksystem.security.PasswordPolicy.validate(form.newPassword());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        userMapper.updatePassword(user.getId(), passwordEncoder.encode(form.newPassword()));
        // [UX35] 초기/재설정 비밀번호 강제 변경 완료 — 플래그 해제
        userMapper.updateMustChangePassword(user.getId(), false);
    }
    @Transactional
    public void updateBasicInfo(CustomUserDetails currentUser, ProfileInfoForm form) {
        UserAccount user = requireUser(currentUser);
        // [B9] 본인이 고친 것도 이력에 남는다 — 인사카드에서 '본인 수정'으로 구분해 보인다.
        UserAccount before = UserChangeLogService.snapshot(user);
        user.setDept(trimToNull(form.dept()));
        user.setExtensionNumber(trimToNull(form.extensionNumber()));
        user.setMobileNumber(trimToNull(form.mobileNumber()));
        user.setJobTitle(trimToNull(form.jobTitle()));
        user.setEmail(trimToNull(form.email()));
        userMapper.updateBasicInfo(user);
        userChangeLogService.record(before, user, "SELF", currentUser);
        currentUser.updateBasicInfo(user.getDept(), user.getExtensionNumber(), user.getMobileNumber(), user.getJobTitle(), user.getEmail());
        panelRefreshPublisher.publishAllActive("profile-basic-info");
    }

    @Transactional
    public void updateProfileImage(CustomUserDetails currentUser, MultipartFile file) {
        UserAccount user = requireUser(currentUser);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("등록할 프로필 사진을 선택하세요.");
        }
        if (file.getSize() > MAX_PROFILE_IMAGE_BYTES) {
            throw new BusinessException("프로필 사진은 2MB 이하만 등록할 수 있습니다.");
        }

        ImageType imageType = detectImageType(file);
        validateContentType(file, imageType);

        Path root = profileImageRoot();
        String filename = user.getId() + "-" + UUID.randomUUID() + "." + imageType.extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("프로필 사진 저장 경로가 올바르지 않습니다.");
        }

        try {
            Files.createDirectories(root);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("프로필 사진 저장에 실패했습니다.");
        }
        AfterCommit.deleteUploadOnRollback(target, "프로필 사진");

        String oldFilename = user.getProfileImageFilename();
        userMapper.updateProfileImageFilename(user.getId(), filename);
        currentUser.updateProfileImageFilename(filename);
        // [O-11] 트리 전체 refresh 대신 사진만 in-place 갱신되는 이벤트만 발송
        panelRefreshPublisher.publishProfileChanged(user.getId(), filename);
        deleteStoredProfileImageQuietly(oldFilename);
    }

    @Transactional
    public void deleteProfileImage(CustomUserDetails currentUser) {
        UserAccount user = requireUser(currentUser);
        if (!StringUtils.hasText(user.getProfileImageFilename())) {
            throw new BusinessException("삭제할 프로필 사진이 없습니다.");
        }
        String oldFilename = user.getProfileImageFilename();
        userMapper.updateProfileImageFilename(user.getId(), null);
        currentUser.updateProfileImageFilename(null);
        panelRefreshPublisher.publishProfileChanged(user.getId(), null);
        deleteStoredProfileImageQuietly(oldFilename);
    }

    public StoredProfileImage loadProfileImage(String filename) {
        String safeFilename = validateStoredFilename(filename);
        Path root = profileImageRoot();
        Path file = root.resolve(safeFilename).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("프로필 사진을 찾을 수 없습니다.");
        }
        try {
            Resource resource = new UrlResource(file.toUri());
            return new StoredProfileImage(resource, mediaTypeFor(safeFilename));
        } catch (IOException e) {
            throw new BusinessException("프로필 사진을 읽을 수 없습니다.");
        }
    }


    @Transactional(readOnly = true)
    public String signatureFilename(CustomUserDetails currentUser) {
        UserAccount user = userMapper.findById(currentUser.getUserId());
        return user == null ? null : user.getSignatureFilename();
    }

    @Transactional(readOnly = true)
    public String signatureFilenameForUser(Long userId) {
        UserAccount user = userMapper.findById(userId);
        return user == null ? null : user.getSignatureFilename();
    }

    @Transactional
    public void updateSignatureImage(CustomUserDetails currentUser, MultipartFile file) {
        UserAccount user = requireUser(currentUser);
        saveSignatureForUser(user, file);
    }

    @Transactional
    public void updateSignatureImageForUser(CustomUserDetails currentUser, Long targetUserId, MultipartFile file) {
        requireAdminActor(currentUser);
        UserAccount target = userMapper.findById(targetUserId);
        if (target == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        saveSignatureForUser(target, file);
    }

    private void saveSignatureForUser(UserAccount user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("등록할 서명 이미지를 선택하세요.");
        }
        if (file.getSize() > MAX_SIGNATURE_BYTES) {
            throw new BusinessException("서명 이미지는 1MB 이하만 등록할 수 있습니다.");
        }
        ImageType imageType = detectImageType(file);
        validateContentType(file, imageType);
        Path root = signatureRoot();
        String filename = user.getId() + "-" + UUID.randomUUID() + "." + imageType.extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("서명 저장 경로가 올바르지 않습니다.");
        }
        try {
            Files.createDirectories(root);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("서명 저장에 실패했습니다.");
        }
        AfterCommit.deleteUploadOnRollback(target, "서명 이미지");
        String oldFilename = user.getSignatureFilename();
        userMapper.updateSignatureFilename(user.getId(), filename);
        deleteStoredSignatureQuietly(oldFilename);
    }

    @Transactional
    public void updateSignatureFromDataUrl(CustomUserDetails currentUser, String dataUrl) {
        UserAccount user = requireUser(currentUser);
        if (!StringUtils.hasText(dataUrl) || !dataUrl.startsWith("data:image/png;base64,")) {
            throw new BusinessException("그린 서명 데이터가 올바르지 않습니다.");
        }
        byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("그린 서명 데이터가 올바르지 않습니다.");
        }
        if (bytes.length == 0 || bytes.length > MAX_SIGNATURE_BYTES) {
            throw new BusinessException("서명 이미지는 1MB 이하만 등록할 수 있습니다.");
        }
        if (!(bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47)) {
            throw new BusinessException("서명 이미지 형식이 올바르지 않습니다.");
        }
        Path root = signatureRoot();
        String filename = user.getId() + "-" + UUID.randomUUID() + ".png";
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("서명 저장 경로가 올바르지 않습니다.");
        }
        try {
            Files.createDirectories(root);
            storageBudget.write(target, bytes);
        } catch (IOException e) {
            throw new BusinessException("서명 저장에 실패했습니다.");
        }
        AfterCommit.deleteUploadOnRollback(target, "서명 이미지");
        String oldFilename = user.getSignatureFilename();
        userMapper.updateSignatureFilename(user.getId(), filename);
        deleteStoredSignatureQuietly(oldFilename);
    }

    @Transactional
    public void deleteSignature(CustomUserDetails currentUser) {
        UserAccount user = requireUser(currentUser);
        clearSignature(user);
    }

    @Transactional
    public void deleteSignatureForUser(CustomUserDetails currentUser, Long targetUserId) {
        requireAdminActor(currentUser);
        UserAccount target = userMapper.findById(targetUserId);
        if (target == null) {
            throw new BusinessException("대상 직원을 찾을 수 없습니다.");
        }
        clearSignature(target);
    }

    private void clearSignature(UserAccount user) {
        if (!StringUtils.hasText(user.getSignatureFilename())) {
            throw new BusinessException("삭제할 서명이 없습니다.");
        }
        String oldFilename = user.getSignatureFilename();
        userMapper.updateSignatureFilename(user.getId(), null);
        deleteStoredSignatureQuietly(oldFilename);
    }

    /**
     * 결재 스냅샷용으로 원본 서명 이미지를 같은 저장소에 별도 파일명으로 복제한다.
     * 사용자가 마이페이지에서 원본 서명을 삭제해도 스냅샷은 영구 유지된다.
     * 반환: 새 파일명. 원본이 없거나 실패 시 null.
     */
    public String copySignatureForSnapshot(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) return null;
        try {
            String safe = validateStoredFilename(originalFilename);
            Path root = signatureRoot();
            Path source = root.resolve(safe).normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)) return null;
            int dot = safe.lastIndexOf('.');
            String ext = dot >= 0 ? safe.substring(dot) : "";
            String snapName = "snap_" + java.util.UUID.randomUUID().toString().replace("-", "") + ext;
            Path target = root.resolve(snapName).normalize();
            if (!target.startsWith(root)) return null;
            storageBudget.copy(source, target);
            // 스냅샷 복제는 결재 확정(@Transactional) 안에서 불린다 — 그 트랜잭션이 엎어지면
            // 복제본만 남는다. 원본과 이름이 달라 아무도 참조하지 않는다.
            AfterCommit.deleteUploadOnRollback(target, "서명 스냅샷");
            return snapName;
        } catch (IOException | BusinessException e) {
            return null;
        }
    }

    public StoredProfileImage loadSignature(String filename) {
        String safeFilename = validateStoredFilename(filename);
        Path root = signatureRoot();
        Path file = root.resolve(safeFilename).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("서명을 찾을 수 없습니다.");
        }
        try {
            return new StoredProfileImage(new UrlResource(file.toUri()), mediaTypeFor(safeFilename));
        } catch (IOException e) {
            throw new BusinessException("서명을 읽을 수 없습니다.");
        }
    }

    public String signatureDataUriForSnapshot(String filename) {
        if (!StringUtils.hasText(filename)) {
            return null;
        }
        try {
            String safeFilename = validateStoredFilename(filename);
            Path root = signatureRoot();
            Path file = root.resolve(safeFilename).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return null;
            }
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            return "data:" + mediaTypeFor(safeFilename) + ";base64," + encoded;
        } catch (IOException | BusinessException e) {
            return null;
        }
    }

    /**
     * 공지 본문 에디터의 이미지 업로드 — 파일명만 돌려주고 본문에는 나중에 저장된다.
     *
     * <p>여기에는 롤백 정리({@code AfterCommit.deleteUploadOnRollback})를 붙이지 않는다.
     * 트랜잭션이 없기도 하지만, 무엇보다 <b>이 파일은 요청이 끝난 뒤에도 살아 있어야</b>
     * 하기 때문이다 — 작성자가 이미지를 넣고 공지를 저장하는 것은 그 다음 요청이다.
     *
     * <p>대신 <b>공지를 저장하지 않고 창을 닫으면</b> 아무도 참조하지 않는 파일이 남는다.
     * 이건 롤백 문제가 아니라 에디터 업로드의 성격이라, 고치려면 본문에서 참조되지 않는
     * 이미지를 주기적으로 쓸어내는 별도 정리가 필요하다.
     */
    public String storeNoticeImage(CustomUserDetails currentUser, MultipartFile file) {
        requireAdminActor(currentUser);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("등록할 이미지를 선택하세요.");
        }
        if (file.getSize() > MAX_NOTICE_IMAGE_BYTES) {
            throw new BusinessException("이미지는 5MB 이하만 등록할 수 있습니다.");
        }
        ImageType imageType = detectImageType(file);
        validateContentType(file, imageType);
        Path root = noticeImageRoot();
        String filename = "notice-" + UUID.randomUUID() + "." + imageType.extension;
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("이미지 저장 경로가 올바르지 않습니다.");
        }
        try {
            Files.createDirectories(root);
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BusinessException("이미지 저장에 실패했습니다.");
        }
        return filename;
    }

    public StoredProfileImage loadNoticeImage(String filename) {
        String safeFilename = validateNoticeFilename(filename);
        Path root = noticeImageRoot();
        Path file = root.resolve(safeFilename).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BusinessException("이미지를 찾을 수 없습니다.");
        }
        try {
            return new StoredProfileImage(new UrlResource(file.toUri()), mediaTypeFor(safeFilename));
        } catch (IOException e) {
            throw new BusinessException("이미지를 읽을 수 없습니다.");
        }
    }

    private Path noticeImageRoot() {
        return Path.of(noticeImageDir).toAbsolutePath().normalize();
    }

    private String validateNoticeFilename(String filename) {
        if (!StringUtils.hasText(filename) || !filename.matches("^notice-[0-9a-fA-F-]{36}\\.(jpg|png|webp)$")) {
            throw new BusinessException("이미지 경로가 올바르지 않습니다.");
        }
        return filename;
    }

    private void requireAdminActor(CustomUserDetails currentUser) {
        if (currentUser == null
                || !(currentUser.isSystemAdmin() || currentUser.hasRole("HR_ADMIN") || currentUser.hasRole("ERP_ADMIN"))) {
            throw new BusinessException("관리자만 다른 직원의 서명을 관리할 수 있습니다.");
        }
    }

    private Path signatureRoot() {
        return Path.of(signatureDir).toAbsolutePath().normalize();
    }

    /**
     * 서명 이미지 파일 삭제 — <b>커밋 후에</b> 실행한다.
     *
     * <p>트랜잭션 안에서 먼저 지우면, 뒤이은 DB 작업이 실패해 롤백될 때 파일만 사라지고
     * 레코드는 남는다. 화면에는 파일명이 그대로 보이는데 열면 깨진다.
     *
     * <p>경로 검증은 <b>등록 시점에</b> 끝낸다 — 커밋 후에는 예외를 던져도 되돌릴 것이 없다.
     */
    private void deleteStoredSignatureQuietly(String filename) {
        if (!StringUtils.hasText(filename)) {
            return;
        }
        final Path file;
        try {
            String safeFilename = validateStoredFilename(filename);
            Path root = signatureRoot();
            Path resolved = root.resolve(safeFilename).normalize();
            if (!resolved.startsWith(root)) {
                return;
            }
            file = resolved;
        } catch (Exception invalidPath) {
            return;
        }
        AfterCommit.run(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                log.warn("서명 이미지 파일 삭제 실패: {} ({})", file, e.getMessage());
            }
        });
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
    private UserAccount requireUser(CustomUserDetails currentUser) {
        UserAccount user = userMapper.findById(currentUser.getUserId());
        if (user == null) {
            throw new BusinessException("사용자를 찾을 수 없습니다.");
        }
        return user;
    }

    private ImageType detectImageType(MultipartFile file) {
        byte[] header;
        try (InputStream inputStream = file.getInputStream()) {
            header = inputStream.readNBytes(16);
        } catch (IOException e) {
            throw new BusinessException("프로필 사진을 읽을 수 없습니다.");
        }
        if (header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) {
            return ImageType.JPG;
        }
        if (header.length >= 8
                && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47
                && header[4] == 0x0d && header[5] == 0x0a && header[6] == 0x1a && header[7] == 0x0a) {
            return ImageType.PNG;
        }
        if (header.length >= 12
                && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
            return ImageType.WEBP;
        }
        throw new BusinessException("JPG, PNG, WebP 형식의 이미지만 등록할 수 있습니다.");
    }

    private void validateContentType(MultipartFile file, ImageType imageType) {
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        if (!imageType.contentType.equals(normalized)) {
            throw new BusinessException("파일 형식과 이미지 내용이 일치하지 않습니다.");
        }
    }

    private Path profileImageRoot() {
        return Path.of(profileImageDir).toAbsolutePath().normalize();
    }

    private String validateStoredFilename(String filename) {
        if (!StringUtils.hasText(filename)
                || !filename.matches("^(?:[0-9]+-[0-9a-fA-F-]{36}|snap_[0-9a-fA-F]{32})\\.(jpg|png|webp)$")) {
            throw new BusinessException("프로필 사진 경로가 올바르지 않습니다.");
        }
        return filename;
    }

    private MediaType mediaTypeFor(String filename) {
        if (filename.endsWith(".jpg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (filename.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        return MediaType.parseMediaType("image/webp");
    }

    /**
     * 프로필 사진 파일 삭제 — <b>커밋 후에</b> 실행한다.
     *
     * <p>트랜잭션 안에서 먼저 지우면, 뒤이은 DB 작업이 실패해 롤백될 때 파일만 사라지고
     * 레코드는 남는다. 화면에는 파일명이 그대로 보이는데 열면 깨진다.
     *
     * <p>경로 검증은 <b>등록 시점에</b> 끝낸다 — 커밋 후에는 예외를 던져도 되돌릴 것이 없다.
     */
    private void deleteStoredProfileImageQuietly(String filename) {
        if (!StringUtils.hasText(filename)) {
            return;
        }
        final Path file;
        try {
            String safeFilename = validateStoredFilename(filename);
            Path root = profileImageRoot();
            Path resolved = root.resolve(safeFilename).normalize();
            if (!resolved.startsWith(root)) {
                return;
            }
            file = resolved;
        } catch (Exception invalidPath) {
            return;
        }
        AfterCommit.run(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                log.warn("프로필 사진 파일 삭제 실패: {} ({})", file, e.getMessage());
            }
        });
    }

    public record StoredProfileImage(Resource resource, MediaType mediaType) {
    }

    private enum ImageType {
        JPG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp");

        private final String extension;
        private final String contentType;

        ImageType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }
    }
}
