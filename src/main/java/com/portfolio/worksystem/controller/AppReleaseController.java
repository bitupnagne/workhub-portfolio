package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.dto.AppReleaseCheckResponse;
import com.portfolio.worksystem.model.AppRelease;
import com.portfolio.worksystem.model.AppReleasePatch;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.AppReleaseService;
import com.portfolio.worksystem.service.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Controller
public class AppReleaseController {
    private final AppReleaseService appReleaseService;
    private final com.portfolio.worksystem.service.OrganizationService organizationService;

    @GetMapping("/admin/releases")
    public String releases(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user, "앱 업데이트 관리", "releases");
        model.addAttribute("releases", appReleaseService.findAll());
        return "admin/releases";
    }

    @PostMapping("/admin/releases")
    public String upload(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam String version,
                         @RequestParam(required = false, defaultValue = "FULL") String packageType,
                         @RequestParam(required = false, defaultValue = "false") boolean mandatory,
                         @RequestParam(required = false) List<String> patchBaseVersion,
                         @RequestParam(required = false) String releaseNote,
                         @RequestParam("file") MultipartFile file,
                         @RequestParam(value = "patchFile", required = false) MultipartFile patchFile,
                         RedirectAttributes ra) {
        try {
            AppRelease release = appReleaseService.upload(user, version, packageType, mandatory, patchBaseVersion, releaseNote, file, patchFile);
            ra.addFlashAttribute("message", release.getVersion() + " 버전을 등록했습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/releases";
    }

    @PostMapping("/admin/releases/{id}/deactivate")
    public String deactivate(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             RedirectAttributes ra) {
        try {
            appReleaseService.deactivate(user, id);
            ra.addFlashAttribute("message", "해당 버전을 비활성화했습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/releases";
    }

    @PostMapping("/admin/releases/{id}/activate")
    public String activate(@AuthenticationPrincipal CustomUserDetails user,
                           @PathVariable Long id,
                           RedirectAttributes ra) {
        try {
            appReleaseService.activate(user, id);
            ra.addFlashAttribute("message", "해당 버전을 재활성화했습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/releases";
    }

    @PostMapping("/admin/releases/{id}/patch")
    public String addPatch(@AuthenticationPrincipal CustomUserDetails user,
                           @PathVariable Long id,
                           @RequestParam List<String> patchBaseVersion,
                           @RequestParam("patchFile") MultipartFile patchFile,
                           RedirectAttributes ra) {
        try {
            appReleaseService.addPatch(user, id, patchBaseVersion, patchFile);
            ra.addFlashAttribute("message", "패치파일을 추가했습니다.");
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/releases";
    }

    @GetMapping("/api/desktop/releases/latest")
    @ResponseBody
    public AppReleaseCheckResponse latest(@RequestParam(required = false, defaultValue = "0.0.0") String currentVersion) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return appReleaseService.latestFor(currentVersion, baseUrl);
    }

    @GetMapping("/api/desktop/releases/{version}/note")
    @ResponseBody
    public Map<String, Object> releaseNote(@PathVariable String version) {
        AppRelease release = appReleaseService.findReleaseNote(version);
        return Map.of(
                "version", release.getVersion(),
                "releaseNote", release.getReleaseNote() == null ? "" : release.getReleaseNote()
        );
    }

    @GetMapping("/api/desktop/releases/latest/download")
    public ResponseEntity<Resource> latestDownload() {
        AppRelease release = appReleaseService.findLatestDownloadable();
        return downloadResponse(release);
    }

    @GetMapping("/api/desktop/releases/{version}/download")
    public ResponseEntity<Resource> download(@PathVariable String version) {
        AppRelease release = appReleaseService.findDownloadable(version);
        return downloadResponse(release);
    }

    @GetMapping("/api/desktop/releases/{version}/patch")
    public ResponseEntity<Resource> patch(@PathVariable String version,
                                          @RequestParam(required = false) String baseVersion) {
        AppReleasePatch patch = appReleaseService.findPatchDownloadable(version, baseVersion);
        Resource resource = appReleaseService.downloadPatchResource(patch);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(patch.getFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(patch.getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Checksum-SHA256", patch.getSha256())
                .body(resource);
    }

    private ResponseEntity<Resource> downloadResponse(AppRelease release) {
        Resource resource = appReleaseService.downloadResource(release);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(release.getStoredFileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(release.getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Checksum-SHA256", release.getSha256())
                .body(resource);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<String> businessException(BusinessException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String maxUploadSizeExceeded(MaxUploadSizeExceededException e, RedirectAttributes ra) {
        ra.addFlashAttribute("error", "설치파일이 200MB를 초과합니다. 패치 ZIP으로 나눠 배포해 주세요.");
        return "redirect:/admin/releases";
    }

    private void addCommon(Model model, CustomUserDetails user, String title, String active) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("active", active);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }
}
