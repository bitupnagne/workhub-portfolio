package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.model.AdminNotice;
import com.portfolio.worksystem.model.Notification;
import com.portfolio.worksystem.service.CommunicationService;
import com.portfolio.worksystem.service.OrganizationService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@RequiredArgsConstructor
@Controller
public class NotificationController {
    private final CommunicationService communicationService;
    private final OrganizationService organizationService;

    @GetMapping("/notifications")
    public String notifications(@AuthenticationPrincipal CustomUserDetails user,
                                @RequestParam(required = false, name = "q") String query,
                                @RequestParam(defaultValue = "all") String status,
                                @RequestParam(defaultValue = "1") int page,
                                Model model) {
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        String statusFilter = StringUtils.hasText(status) ? status.trim() : "all";
        if (!java.util.Set.of("all", "unread", "read").contains(statusFilter)) {
            statusFilter = "all";
        }
        final String effectiveStatusFilter = statusFilter;
        java.util.List<Notification> filtered = communicationService.notifications(user).stream()
                .filter(n -> !"MESSAGE".equalsIgnoreCase(n.getType()))
                .filter(n -> "all".equals(effectiveStatusFilter)
                        || ("unread".equals(effectiveStatusFilter) && n.isUnread())
                        || ("read".equals(effectiveStatusFilter) && !n.isUnread()))
                .filter(n -> keyword.isEmpty()
                        || contains(n.getTitle(), keyword)
                        || contains(n.getPlainBody(), keyword)
                        || contains(n.getSenderName(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, filtered.size());
        addCommon(model, user, "알림", "notifications");
        model.addAttribute("notifications", com.portfolio.worksystem.model.PageInfo.slice(filtered, pageInfo));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/notifications?q="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                + "&status=" + statusFilter + "&page=");
        model.addAttribute("query", keyword);
        model.addAttribute("statusFilter", statusFilter);
        return "communication/notifications";
    }

    /** 공지사항 목록(사용자) — 알림 목록과 별개. 관리자 공지목록을 복제하되 작성/수정/삭제·상태필터는 없음. */
    @GetMapping("/notices")
    public String notices(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam(required = false, name = "q") String query,
                          @RequestParam(defaultValue = "1") int page,
                          Model model) {
        String keyword = StringUtils.hasText(query) ? query.trim() : "";
        java.util.List<AdminNotice> all = communicationService.activeNotices().stream()
                .filter(n -> keyword.isEmpty()
                        || contains(n.getTitle(), keyword)
                        || contains(n.getPlainBody(), keyword)
                        || contains(n.getCreatedByName(), keyword))
                .toList();
        com.portfolio.worksystem.model.PageInfo pageInfo = new com.portfolio.worksystem.model.PageInfo(page, 20, all.size());
        addCommon(model, user, "공지사항", "notifications");
        model.addAttribute("notices", com.portfolio.worksystem.model.PageInfo.slice(all, pageInfo));
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("pageBaseUrl", "/notices?q="
                + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&page=");
        model.addAttribute("query", keyword);
        return "communication/notices";
    }

    @GetMapping("/notices/{id}")
    public String noticeDetail(@AuthenticationPrincipal CustomUserDetails user,
                               @PathVariable Long id,
                               Model model) {
        AdminNotice notice = communicationService.publicNotice(id, user);
        addCommon(model, user, "공지", "notifications");
        model.addAttribute("notice", notice);
        model.addAttribute("noticeAuthor", communicationService.userBrief(notice.getCreatedBy()));
        model.addAttribute("noticeBodyHtml", communicationService.noticeBodyHtml(notice));
        model.addAttribute("poll", communicationService.noticePoll(id, user));
        model.addAttribute("comments", communicationService.noticeComments(id));
        model.addAttribute("allowAnonymous", Boolean.TRUE.equals(notice.getAllowAnonymous()));
        model.addAttribute("canManageNotice", organizationService.canManageNotice(user));
        return "communication/notice-detail";
    }

    @PostMapping("/notices/{id}/vote")
    public String votePoll(@AuthenticationPrincipal CustomUserDetails user,
                           @PathVariable Long id,
                           @org.springframework.web.bind.annotation.RequestParam Long optionId,
                           RedirectAttributes ra) {
        try {
            communicationService.votePoll(user, id, optionId);
            ra.addFlashAttribute("message", "투표가 완료되었습니다.");
        } catch (com.portfolio.worksystem.service.BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/notices/" + id;
    }

    @PostMapping("/notices/{id}/comment")
    public String addComment(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long id,
                             @org.springframework.web.bind.annotation.RequestParam String body,
                             @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean anonymous,
                             @org.springframework.web.bind.annotation.RequestParam(required = false) Long parentId,
                             RedirectAttributes ra) {
        try {
            communicationService.addNoticeComment(user, id, body, Boolean.TRUE.equals(anonymous), parentId);
            ra.addFlashAttribute("message", "댓글을 등록했습니다.");
        } catch (com.portfolio.worksystem.service.BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/notices/" + id;
    }

    @PostMapping("/notices/{id}/comment/{commentId}/delete")
    public String deleteComment(@AuthenticationPrincipal CustomUserDetails user,
                                @PathVariable Long id,
                                @PathVariable Long commentId,
                                RedirectAttributes ra) {
        try {
            communicationService.deleteNoticeComment(user, commentId);
            ra.addFlashAttribute("message", "댓글을 삭제했습니다.");
        } catch (com.portfolio.worksystem.service.BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/notices/" + id;
    }

    @PostMapping("/notifications/{notificationId}/read")
    public String markRead(@AuthenticationPrincipal CustomUserDetails user,
                           @PathVariable Long notificationId,
                           RedirectAttributes ra) {
        communicationService.markNotificationRead(user, notificationId);
        ra.addFlashAttribute("message", "알림을 읽음 처리했습니다.");
        return "redirect:/notifications";
    }

    @PostMapping("/notifications/{notificationId}/delete")
    public String deleteNotification(@AuthenticationPrincipal CustomUserDetails user,
                                     @PathVariable Long notificationId,
                                     @RequestParam(required = false) String back,
                                     RedirectAttributes ra) {
        communicationService.deleteNotification(user, notificationId);
        ra.addFlashAttribute("message", "알림을 삭제했습니다.");
        return "redirect:" + (back != null && back.startsWith("/notifications") ? back : "/notifications");
    }

    @PostMapping("/notifications/read-all")
    public String markAllRead(@AuthenticationPrincipal CustomUserDetails user,
                              RedirectAttributes ra) {
        communicationService.markAllNotificationsRead(user);
        ra.addFlashAttribute("message", "모든 알림을 읽음 처리했습니다.");
        return "redirect:/notifications";
    }

    private void addCommon(Model model, CustomUserDetails user, String title, String active) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("active", active);
        model.addAttribute("showWeekControl", false);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value)
                && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }
}
