package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.BusinessException;
import com.portfolio.worksystem.service.EmpGradeService;
import com.portfolio.worksystem.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 직급 마스터 관리 — 값·단위·순서·통상시급. */
@RequiredArgsConstructor
@Controller
public class GradeAdminController {
    private final EmpGradeService empGradeService;
    private final OrganizationService organizationService;

    @GetMapping("/admin/grades")
    public String grades(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        addCommon(model, user);
        java.util.List<com.portfolio.worksystem.model.EmpGrade> grades = empGradeService.gradeTree(user);
        model.addAttribute("grades", grades);
        // [GRADE] 단계별 단위 — 지금 트리의 가장 깊은 단계 + 한 단계 더(새 단계를 미리 정할 수 있게).
        java.util.Map<Integer, String> units = empGradeService.depthUnits();
        int maxDepth = grades.stream().mapToInt(com.portfolio.worksystem.model.EmpGrade::getDepth).max().orElse(0);
        maxDepth = Math.max(maxDepth, units.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));
        java.util.List<DepthUnit> depthUnits = new java.util.ArrayList<>();
        for (int depth = 0; depth <= Math.min(maxDepth + 1, 9); depth++) {
            depthUnits.add(new DepthUnit(depth, units.getOrDefault(depth, "")));
        }
        model.addAttribute("depthUnits", depthUnits);
        return "admin/grades";
    }

    /** [GRADE] 단계(0=최상위)와 그 단계의 기본 단위. */
    public record DepthUnit(int depth, String unit) {
        public int getLevel() {
            return depth + 1;
        }
    }

    @PostMapping("/admin/grades/units")
    public String saveDepthUnits(@AuthenticationPrincipal CustomUserDetails user,
                                 @RequestParam java.util.Map<String, String> params,
                                 RedirectAttributes ra) {
        java.util.Map<Integer, String> units = new java.util.TreeMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("unit_")) {
                try {
                    units.put(Integer.valueOf(key.substring("unit_".length())), value);
                } catch (NumberFormatException ignored) {
                    // 단계 번호가 아닌 파라미터는 무시한다.
                }
            }
        });
        try {
            ra.addFlashAttribute("message", empGradeService.saveDepthUnits(units, user));
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/grades";
    }

    @PostMapping("/admin/grades/{id}/move")
    public String moveGrade(@AuthenticationPrincipal CustomUserDetails user,
                            @PathVariable Long id,
                            @RequestParam String direction,
                            RedirectAttributes ra) {
        try {
            empGradeService.moveGrade(id, "up".equals(direction), user);
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/grades";
    }

    @PostMapping("/admin/grades")
    public String createGrade(@AuthenticationPrincipal CustomUserDetails user,
                              @RequestParam(required = false) String value,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String unitLabel,
                              @RequestParam(defaultValue = "inherit") String unitMode,
                              @RequestParam(required = false) String displayOrder,
                              @RequestParam(required = false) String parentId,
                              RedirectAttributes ra) {
        run(() -> empGradeService.createGrade(value != null ? value : name, unit(unitMode, unitLabel),
                num(displayOrder), id(parentId), user), "직급을 추가했습니다.", ra);
        return "redirect:/admin/grades";
    }

    @PostMapping("/admin/grades/{id}/update")
    public String updateGrade(@AuthenticationPrincipal CustomUserDetails user,
                              @PathVariable Long id,
                              @RequestParam(required = false) String value,
                              @RequestParam(required = false) String name,
                              @RequestParam(required = false) String unitLabel,
                              @RequestParam(defaultValue = "inherit") String unitMode,
                              @RequestParam(required = false) String displayOrder,
                              @RequestParam(defaultValue = "false") boolean active,
                              @RequestParam(required = false) String parentId,
                              RedirectAttributes ra) {
        run(() -> empGradeService.updateGrade(id, value != null ? value : name, unit(unitMode, unitLabel),
                num(displayOrder), active, id(parentId), user), "직급을 저장했습니다.", ra);
        return "redirect:/admin/grades";
    }

    @PostMapping("/admin/grades/bulk-assign")
    public String bulkAssign(@AuthenticationPrincipal CustomUserDetails user, RedirectAttributes ra) {
        try {
            ra.addFlashAttribute("message", empGradeService.bulkAssignByOrg(user));
        } catch (com.portfolio.worksystem.service.BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/grades";
    }

    @PostMapping("/admin/grades/{id}/delete")
    public String deleteGrade(@AuthenticationPrincipal CustomUserDetails user,
                              @PathVariable Long id,
                              RedirectAttributes ra) {
        run(() -> empGradeService.deleteGrade(id, user), "등급을 삭제했습니다.", ra);
        return "redirect:/admin/grades";
    }

    /**
     * '— 최상위 —' 를 고르면 폼이 {@code parentId=""} 를 보낸다. 이걸 Long 으로 바로 받으면
     * 타입 변환이 실패해 <b>400</b> 이 되고, 화면에는 아무 안내 없이 흰 오류 페이지가 뜬다.
     * 비어 있으면 null(최상위)로 읽는다. 숫자 입력칸을 지운 경우도 마찬가지다.
     */
    /**
     * [GRADE] 단위 선택 — inherit(단계 기본) → null, none(붙이지 않음) → "", custom(직접) → 입력값.
     */
    private static String unit(String mode, String label) {
        return switch (mode) {
            case "none" -> "";
            case "custom" -> label == null ? "" : label;
            default -> null;
        };
    }

    private Long id(String raw) {
        Integer parsed = num(raw);
        return parsed == null ? null : parsed.longValue();
    }

    private Integer num(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("숫자를 입력하세요.");
        }
    }

    private void run(Runnable action, String successMessage, RedirectAttributes ra) {
        try {
            action.run();
            ra.addFlashAttribute("message", successMessage);
        } catch (BusinessException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
    }

    private void addCommon(Model model, CustomUserDetails user) {
        model.addAttribute("currentUser", user);
        model.addAttribute("title", "직급 관리");
        model.addAttribute("active", "grades");
        model.addAttribute("reportAdminTeam", organizationService.getReportAdminTeam());
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }
}
