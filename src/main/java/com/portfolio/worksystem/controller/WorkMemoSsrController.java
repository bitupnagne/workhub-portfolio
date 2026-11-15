package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.model.WorkMemo;
import com.portfolio.worksystem.model.WorkMemoRecipient;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.WorkMemoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class WorkMemoSsrController {

    private final WorkMemoService workMemoService;
    private final UserMapper userMapper;
    private final OrganizationService organizationService;

    private void addCommon(Model model, CustomUserDetails user) {
        model.addAttribute("currentUser", user);
        model.addAttribute("showWeekControl", false);
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }

    @GetMapping("/work-memos")
    public String list(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(value = "box", defaultValue = "received") String box,
                       @RequestParam(value = "q", required = false) String query,
                       @RequestParam(value = "page", defaultValue = "0") int page,
                       Model model) {
        List<WorkMemo> items;
        int total;
        switch (box) {
            case "sent" -> {
                items = workMemoService.sent(user, query, null, null, page);
                total = workMemoService.countSent(user, query, null, null);
            }
            case "starred" -> {
                items = workMemoService.starred(user, query, null, null, page);
                total = workMemoService.countStarred(user, query, null, null);
            }
            default -> {
                items = workMemoService.received(user, query, null, null, page);
                total = workMemoService.countReceived(user, query, null, null);
                box = "received";
            }
        }
        addCommon(model, user);
        model.addAttribute("active", "workMemos");
        model.addAttribute("title", "업무전달");
        model.addAttribute("box", box);
        model.addAttribute("memos", items);
        model.addAttribute("query", query == null ? "" : query);
        model.addAttribute("totalCount", total);
        model.addAttribute("unreadReceived", workMemoService.countUnreadReceived(user));
        model.addAttribute("currentUserId", user.getUserId());
        return "work-memos/list";
    }

    @GetMapping("/work-memos/{memoId}")
    public String detail(@AuthenticationPrincipal CustomUserDetails user,
                         @PathVariable Long memoId,
                         Model model) {
        WorkMemo memo = workMemoService.detail(user, memoId, true);
        List<WorkMemoRecipient> recipients = workMemoService.recipients(user, memoId);
        addCommon(model, user);
        model.addAttribute("active", "workMemos");
        model.addAttribute("title", memo.getTitle());
        model.addAttribute("memo", memo);
        model.addAttribute("recipients", recipients);
        model.addAttribute("currentUserId", user.getUserId());
        return "work-memos/detail";
    }

    @PostMapping("/work-memos/{memoId}/star")
    public String toggleStar(@AuthenticationPrincipal CustomUserDetails user,
                             @PathVariable Long memoId,
                             RedirectAttributes ra) {
        boolean next = workMemoService.toggleStar(user, memoId);
        ra.addFlashAttribute("message", next ? "즐겨찾기에 추가했습니다." : "즐겨찾기에서 제거했습니다.");
        return "redirect:/work-memos/" + memoId;
    }

    @GetMapping("/work-memos/compose")
    public String compose(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        List<UserAccount> all = new ArrayList<>(userMapper.findAllActive());
        all.removeIf(u -> u.getId() != null && u.getId().equals(user.getUserId()));
        addCommon(model, user);
        model.addAttribute("active", "workMemos");
        model.addAttribute("title", "새 업무전달");
        // ⚠ 사람 선택 모달로 넘길 후보는 화면에 JSON 으로 인라인된다 —
        //   UserAccount 를 통째로 내보내면 비밀번호 해시·사번까지 노출된다.
        //   모달이 실제로 쓰는 4개 필드만 담은 슬림 맵으로 내려보낸다.
        List<Map<String, Object>> pickerPeople = new ArrayList<>();
        for (UserAccount u : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getName());
            m.put("teamName", u.getTeamName());
            m.put("teamId", u.getTeamId());
            pickerPeople.add(m);
        }
        model.addAttribute("candidates", pickerPeople);
        // '조직' 탭 트리용 평면 팀 목록도 필요한 3개 필드만.
        List<Map<String, Object>> pickerTeams = new ArrayList<>();
        for (Team t : organizationService.findTeams()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("name", t.getName());
            m.put("parentId", t.getParentId());
            pickerTeams.add(m);
        }
        model.addAttribute("teamNodes", pickerTeams);
        return "work-memos/compose";
    }

    @PostMapping("/work-memos")
    public String create(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam("title") String title,
                         @RequestParam(value = "body", required = false) String body,
                         @RequestParam(value = "recipientIds", required = false) String recipientIdsCsv,
                         @RequestParam(value = "attachments", required = false) List<MultipartFile> attachments,
                         RedirectAttributes ra) {
        List<Long> recipientIds = new ArrayList<>();
        if (recipientIdsCsv != null && !recipientIdsCsv.isBlank()) {
            for (String s : recipientIdsCsv.split(",")) {
                try { recipientIds.add(Long.parseLong(s.trim())); } catch (Exception ignored) {}
            }
        }
        Long id = workMemoService.create(user, title, body == null ? "" : body, recipientIds, "NORMAL", attachments);
        ra.addFlashAttribute("message", "업무전달을 보냈습니다.");
        return "redirect:/work-memos/" + id;
    }
}
