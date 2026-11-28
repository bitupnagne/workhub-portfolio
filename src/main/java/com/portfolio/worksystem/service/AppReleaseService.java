package com.portfolio.worksystem.service;

import com.portfolio.worksystem.dto.AppReleaseCheckResponse;
import com.portfolio.worksystem.mapper.AppReleaseMapper;
import com.portfolio.worksystem.model.AppRelease;
import com.portfolio.worksystem.model.AppReleasePatch;
import com.portfolio.worksystem.security.CustomUserDetails;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@Service
public class AppReleaseService {
    private static final Pattern VERSION_PATTERN = Pattern.compile("\\d+(\\.\\d+){1,3}");

    private final AppReleaseMapper appReleaseMapper;

    @Value("${app.upload.release-dir:releases}")
    private String releaseDir;

    public List<AppRelease> findAll() {
        return withPatches(appReleaseMapper.findAll());
    }

    @Transactional
    public AppRelease upload(CustomUserDetails user,
                             String version,
                             String packageType,
                             boolean mandatory,
                             List<String> patchBaseVersions,
                             String releaseNote,
                             MultipartFile file,
                             MultipartFile patchFile) {
        ensureSystemAdmin(user);
        String normalizedVersion = normalizeVersion(version);
        if (appReleaseMapper.findByVersion(normalizedVersion) != null) {
            throw new BusinessException("이미 등록된 버전입니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("설치파일 EXE를 선택해 주세요.");
        }

        String originalName = cleanOriginalName(file);
        if (!originalName.toLowerCase().endsWith(".exe")) {
            throw new BusinessException("Windows 설치파일(.exe)만 업로드할 수 있습니다.");
        }

        String normalizedPackageType = StringUtils.hasText(packageType) ? packageType.trim().toUpperCase() : "FULL";
        if (!"FULL".equals(normalizedPackageType) && !"PATCH".equals(normalizedPackageType)) {
            throw new BusinessException("배포 방식은 FULL 또는 PATCH만 선택할 수 있습니다.");
        }

        boolean hasPatch = patchFile != null && !patchFile.isEmpty();
        List<String> normalizedPatchBaseVersions = normalizePatchBaseVersions(patchBaseVersions);
        if ("PATCH".equals(normalizedPackageType) && !hasPatch) {
            throw new BusinessException("PATCH 배포에는 패치 ZIP 파일이 필요합니다.");
        }
        if ("PATCH".equals(normalizedPackageType) && normalizedPatchBaseVersions.isEmpty()) {
            throw new BusinessException("PATCH 배포에는 패치 기준 버전이 필요합니다.");
        }
        if ("FULL".equals(normalizedPackageType) && hasPatch) {
            throw new BusinessException("FULL 배포에는 패치 ZIP을 함께 등록할 수 없습니다. PATCH 배포를 선택해 주세요.");
        }
        if ("FULL".equals(normalizedPackageType) && !normalizedPatchBaseVersions.isEmpty()) {
            throw new BusinessException("FULL 배포에는 패치 기준 버전을 입력하지 않습니다.");
        }

        Path root = releaseRoot();
        String storedName = "Portfolio.OfficeSystem-Setup-" + normalizedVersion + ".exe";
        Path target = safeResolve(root, storedName, "저장 경로가 올바르지 않습니다.");

        try {
            Files.createDirectories(root);
            copyUpload(file, target);

            AppRelease release = new AppRelease();
            release.setVersion(normalizedVersion);
            release.setFileName(originalName);
            release.setStoredFileName(storedName);
            release.setSha256(sha256(target));
            release.setFileSize(Files.size(target));
            release.setMandatory(mandatory);
            release.setReleaseNote(StringUtils.hasText(releaseNote) ? releaseNote.trim() : null);
            release.setCreatedBy(user.getUserId());

            AppReleasePatch patch = null;
            if ("PATCH".equals(normalizedPackageType)) {
                patch = savePatchFile(patchFile, normalizedVersion, root);
                applyLegacyPatchColumns(release, patch, normalizedPatchBaseVersions.get(0));
            }

            appReleaseMapper.insert(release);
            if (patch != null) {
                insertPatches(release, patch, normalizedPatchBaseVersions);
            }
            return release;
        } catch (IOException e) {
            throw new BusinessException("배포 파일 저장에 실패했습니다.");
        }
    }

    public AppReleaseCheckResponse latestFor(String currentVersion, String baseUrl) {
        String normalizedCurrent = sanitizeReportedVersion(currentVersion);
        List<AppRelease> candidates = withPatches(appReleaseMapper.findActive()).stream()
                .filter(release -> compareVersions(release.getVersion(), normalizedCurrent) > 0)
                .toList();

        AppRelease latest = candidates.stream()
                .filter(release -> patchFor(release, normalizedCurrent) != null)
                .max(Comparator.comparing(AppRelease::getVersion, AppReleaseService::compareVersions))
                .orElseGet(() -> candidates.stream()
                        .max(Comparator.comparing(AppRelease::getVersion, AppReleaseService::compareVersions))
                        .orElse(null));
        if (latest == null) {
            return AppReleaseCheckResponse.none();
        }

        AppReleasePatch patch = patchFor(latest, normalizedCurrent);
        boolean patchable = patch != null;
        String downloadUrl = patchable
                ? baseUrl + "/api/desktop/releases/" + latest.getVersion() + "/patch?baseVersion="
                + URLEncoder.encode(normalizedCurrent, StandardCharsets.UTF_8)
                : baseUrl + "/api/desktop/releases/" + latest.getVersion() + "/download";

        return new AppReleaseCheckResponse(
                true,
                latest.getVersion(),
                Boolean.TRUE.equals(latest.getMandatory()),
                patchable ? "PATCH" : "FULL",
                downloadUrl,
                patchable ? patch.getSha256() : latest.getSha256(),
                patchable ? patch.getFileSize() : latest.getFileSize(),
                latest.getReleaseNote()
        );
    }

    public AppRelease findDownloadable(String version) {
        AppRelease release = appReleaseMapper.findByVersion(normalizeVersion(version));
        if (release == null || !Boolean.TRUE.equals(release.getActive())) {
            throw new BusinessException("다운로드 가능한 버전이 없습니다.");
        }
        Path file = releaseFile(release);
        if (!Files.isRegularFile(file)) {
            throw new BusinessException("설치파일이 서버에 없습니다.");
        }
        return release;
    }

    public AppRelease findLatestDownloadable() {
        AppRelease release = appReleaseMapper.findActive().stream()
                .max(Comparator.comparing(AppRelease::getVersion, AppReleaseService::compareVersions))
                .orElse(null);
        if (release == null) {
            throw new BusinessException("다운로드 가능한 버전이 없습니다.");
        }
        Path file = releaseFile(release);
        if (!Files.isRegularFile(file)) {
            throw new BusinessException("설치파일이 서버에 없습니다.");
        }
        return release;
    }

    public AppRelease findReleaseNote(String version) {
        AppRelease release = appReleaseMapper.findByVersion(sanitizeReportedVersion(version));
        if (release == null || !Boolean.TRUE.equals(release.getActive())) {
            throw new BusinessException("릴리즈 노트를 찾을 수 없습니다.");
        }
        return release;
    }

    public AppReleasePatch findPatchDownloadable(String version, String baseVersion) {
        AppRelease release = appReleaseMapper.findByVersion(normalizeVersion(version));
        if (release == null || !Boolean.TRUE.equals(release.getActive())) {
            throw new BusinessException("다운로드 가능한 버전이 없습니다.");
        }

        AppReleasePatch patch = StringUtils.hasText(baseVersion)
                ? appReleaseMapper.findPatchByReleaseVersionAndBaseVersion(release.getVersion(), normalizeVersion(baseVersion))
                : singlePatchFor(release.getId());
        if (patch == null) {
            throw new BusinessException("해당 기준 버전에서 적용 가능한 패치파일이 없습니다.");
        }
        Path file = patchFile(patch);
        if (!Files.isRegularFile(file)) {
            throw new BusinessException("패치파일이 서버에 없습니다.");
        }
        return patch;
    }

    public Resource downloadResource(AppRelease release) {
        return new FileSystemResource(releaseFile(release));
    }

    public Resource downloadPatchResource(AppReleasePatch patch) {
        return new FileSystemResource(patchFile(patch));
    }

    @Transactional
    public void deactivate(CustomUserDetails user, Long id) {
        ensureSystemAdmin(user);
        if (appReleaseMapper.findById(id) == null) {
            throw new BusinessException("대상 버전을 찾을 수 없습니다.");
        }
        appReleaseMapper.deactivate(id);
    }

    @Transactional
    public void activate(CustomUserDetails user, Long id) {
        ensureSystemAdmin(user);
        AppRelease release = appReleaseMapper.findById(id);
        if (release == null) {
            throw new BusinessException("대상 버전을 찾을 수 없습니다.");
        }
        if (!Files.isRegularFile(releaseFile(release))) {
            throw new BusinessException("설치파일이 서버에 없어 활성화할 수 없습니다.");
        }
        for (AppReleasePatch patch : appReleaseMapper.findPatchesByReleaseId(release.getId())) {
            if (!Files.isRegularFile(patchFile(patch))) {
                throw new BusinessException("패치파일이 서버에 없어 활성화할 수 없습니다.");
            }
        }
        appReleaseMapper.activate(id);
    }

    @Transactional
    public void addPatch(CustomUserDetails user, Long id, List<String> patchBaseVersions, MultipartFile patchFile) {
        ensureSystemAdmin(user);
        AppRelease release = appReleaseMapper.findById(id);
        if (release == null) {
            throw new BusinessException("대상 버전을 찾을 수 없습니다.");
        }
        if (patchFile == null || patchFile.isEmpty()) {
            throw new BusinessException("패치 ZIP을 선택해 주세요.");
        }
        List<String> normalizedPatchBaseVersions = normalizePatchBaseVersions(patchBaseVersions);
        if (normalizedPatchBaseVersions.isEmpty()) {
            throw new BusinessException("패치 기준 버전을 선택해 주세요.");
        }

        Path root = releaseRoot();
        try {
            Files.createDirectories(root);
            AppReleasePatch patch = savePatchFile(patchFile, release.getVersion(), root);
            applyLegacyPatchColumns(release, patch, normalizedPatchBaseVersions.get(0));
            appReleaseMapper.updatePackageInfo(release);
            insertPatches(release, patch, normalizedPatchBaseVersions);
        } catch (IOException e) {
            throw new BusinessException("패치파일 저장에 실패했습니다.");
        }
    }

    private AppReleasePatch savePatchFile(MultipartFile patchFile,
                                          String normalizedVersion,
                                          Path root) throws IOException {
        String patchOriginalName = cleanOriginalName(patchFile);
        if (!patchOriginalName.toLowerCase().endsWith(".zip")) {
            throw new BusinessException("패치파일은 ZIP만 업로드할 수 있습니다.");
        }
        String patchStoredName = "Portfolio.OfficeSystem-Patch-" + normalizedVersion + ".zip";
        Path patchTarget = safeResolve(root, patchStoredName, "패치 저장 경로가 올바르지 않습니다.");
        copyUpload(patchFile, patchTarget);

        AppReleasePatch patch = new AppReleasePatch();
        patch.setFileName(patchOriginalName);
        patch.setStoredFileName(patchStoredName);
        patch.setSha256(sha256(patchTarget));
        patch.setFileSize(Files.size(patchTarget));
        return patch;
    }

    private void insertPatches(AppRelease release, AppReleasePatch patchFile, List<String> baseVersions) {
        for (String baseVersion : baseVersions) {
            AppReleasePatch patch = new AppReleasePatch();
            patch.setReleaseId(release.getId());
            patch.setBaseVersion(baseVersion);
            patch.setFileName(patchFile.getFileName());
            patch.setStoredFileName(patchFile.getStoredFileName());
            patch.setSha256(patchFile.getSha256());
            patch.setFileSize(patchFile.getFileSize());
            appReleaseMapper.insertPatch(patch);
        }
    }

    private void applyLegacyPatchColumns(AppRelease release, AppReleasePatch patch, String baseVersion) {
        release.setPatchBaseVersion(baseVersion);
        release.setPatchFileName(patch.getFileName());
        release.setPatchStoredFileName(patch.getStoredFileName());
        release.setPatchSha256(patch.getSha256());
        release.setPatchFileSize(patch.getFileSize());
    }

    private List<AppRelease> withPatches(List<AppRelease> releases) {
        releases.forEach(release -> release.setPatches(appReleaseMapper.findPatchesByReleaseId(release.getId())));
        return releases;
    }

    private AppReleasePatch patchFor(AppRelease release, String normalizedCurrent) {
        if (release.getPatches() == null) {
            return null;
        }
        return release.getPatches().stream()
                .filter(patch -> patch.getBaseVersion().equals(normalizedCurrent)
                        && StringUtils.hasText(patch.getStoredFileName())
                        && StringUtils.hasText(patch.getSha256())
                        && patch.getFileSize() != null)
                .findFirst()
                .orElse(null);
    }

    private AppReleasePatch singlePatchFor(Long releaseId) {
        List<AppReleasePatch> patches = appReleaseMapper.findPatchesByReleaseId(releaseId);
        return patches.size() == 1 ? patches.get(0) : null;
    }

    private List<String> normalizePatchBaseVersions(List<String> patchBaseVersions) {
        if (patchBaseVersions == null) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : patchBaseVersions) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            for (String part : value.split("[,\\s]+")) {
                if (StringUtils.hasText(part)) {
                    normalized.add(normalizeVersion(part));
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private void ensureSystemAdmin(CustomUserDetails user) {
        if (user == null || !user.isSystemAdmin()) {
            throw new BusinessException("시스템 관리자만 업데이트를 관리할 수 있습니다.");
        }
    }

    private String normalizeVersion(String version) {
        if (!StringUtils.hasText(version)) {
            throw new BusinessException("버전을 입력해 주세요.");
        }
        String normalized = version.trim();
        if (!VERSION_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException("버전은 1.0.1 형식으로 입력해 주세요.");
        }
        return normalized;
    }

    private static String sanitizeReportedVersion(String version) {
        if (!StringUtils.hasText(version)) {
            return "0.0.0";
        }
        String normalized = version.trim();
        int metadataIndex = normalized.indexOf('+');
        if (metadataIndex >= 0) {
            normalized = normalized.substring(0, metadataIndex);
        }
        int prereleaseIndex = normalized.indexOf('-');
        if (prereleaseIndex >= 0) {
            normalized = normalized.substring(0, prereleaseIndex);
        }
        return StringUtils.hasText(normalized) ? normalized : "0.0.0";
    }

    private Path releaseRoot() {
        return Path.of(releaseDir).toAbsolutePath().normalize();
    }

    private Path releaseFile(AppRelease release) {
        return safeResolve(releaseRoot(), release.getStoredFileName(), "설치파일 경로가 올바르지 않습니다.");
    }

    private Path patchFile(AppReleasePatch patch) {
        return safeResolve(releaseRoot(), patch.getStoredFileName(), "패치파일 경로가 올바르지 않습니다.");
    }

    private static Path safeResolve(Path root, String storedName, String errorMessage) {
        Path file = root.resolve(storedName).normalize();
        if (!file.startsWith(root)) {
            throw new BusinessException(errorMessage);
        }
        return file;
    }

    private static String cleanOriginalName(MultipartFile file) {
        return StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
    }

    /**
     * 업로드 파일을 저장하고, 트랜잭션이 롤백되면 되치운다.
     *
     * <p>저장 이름이 버전으로 <b>고정</b>이라(Portfolio.OfficeSystem-Setup-1.0.26.exe) 되치울 때
     * 조심해야 한다 — 같은 버전을 다시 올리는 경우 이미 있던 파일을 덮어쓴 것이므로,
     * 롤백됐다고 지우면 <b>원래 있던 릴리스까지 사라진다.</b> 그래서 이 호출이
     * <b>새로 만든 경우에만</b> 정리한다.
     */
    private static void copyUpload(MultipartFile file, Path target) throws IOException {
        boolean existedBefore = Files.exists(target);
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!existedBefore) {
            com.portfolio.worksystem.util.AfterCommit.deleteUploadOnRollback(target, "릴리스 패키지");
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(OutputStreamDiscard.INSTANCE);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BusinessException("파일 해시 생성에 실패했습니다.");
        }
    }

    private static int compareVersions(String left, String right) {
        String[] a = left == null ? new String[0] : left.split("\\.");
        String[] b = right == null ? new String[0] : right.split("\\.");
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int ai = i < a.length ? parseVersionPart(a[i]) : 0;
            int bi = i < b.length ? parseVersionPart(b[i]) : 0;
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private static int parseVersionPart(String value) {
        try {
            if (value == null) {
                return 0;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\d+").matcher(value.trim());
            return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {
        private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

        @Override
        public void write(int b) {
        }
    }
}
