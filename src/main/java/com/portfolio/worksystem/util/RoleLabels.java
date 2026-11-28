package com.portfolio.worksystem.util;

import java.util.List;
import java.util.Map;

public final class RoleLabels {
    private static final Map<String, String> LABELS = Map.of(
            "SUPER_ADMIN", "슈퍼 관리자",
            "SYSTEM_ADMIN", "시스템 관리자",
            "REPORT_ADMIN", "주간보고 관리자",
            "HR_ADMIN", "HR 관리자",
            "ERP_ADMIN", "ERP 관리자",
            "NOTICE_ADMIN", "공지 관리자",
            "TEAM_LEADER", "팀장",
            "EMPLOYEE", "직원");

    private RoleLabels() {
    }

    public static String label(String code) {
        if (code == null) {
            return "";
        }
        return LABELS.getOrDefault(code, code);
    }

    public static String labels(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label(code));
        }
        return sb.toString();
    }
}
