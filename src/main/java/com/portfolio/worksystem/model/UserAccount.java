package com.portfolio.worksystem.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class UserAccount {
    private Long id;
    private String username;
    private String employeeNumber;
    private String password;
    private String name;
    private Long teamId;
    private String teamCode;
    private String teamName;
    private String dept;
    private String extensionNumber;
    private String mobileNumber;
    private String jobTitle;
    private Long gradeId;
    private String gradeName;
    private String email;
    private java.time.LocalDate hireDate;
    /**
     * [B8] 퇴사일. active 와 분리해 둔다 — 퇴사일이 있어도 인수인계 기간 동안 계정을 열어 두는
     * 운영이 있을 수 있고, 반대로 퇴사일 없이 비활성만 된 옛 데이터도 그대로 살아 있어야 한다.
     * 그 해 연차를 재직 기간에 비례해 정산하는 기준이 된다.
     */
    private java.time.LocalDate resignedOn;
    /** [B6] 휴직 기간 — active=TRUE이면서 기간 내면 '휴직'. loaEnd null = 복직일 미정 */
    private java.time.LocalDate loaStart;
    private java.time.LocalDate loaEnd;
    /** [B7] 현재 휴직의 사유(loa_types.code). 연차 출근율 반영이 이 값으로 갈린다. 옛 데이터는 null. */
    private String loaType;
    private String profileImageFilename;
    private String signatureFilename;
    private Boolean active;
    private Boolean systemAccount;
    private Boolean contractor;
    private Integer memberOrder;
    private String orgRole;
    private String otpSecret;
    private Boolean otpEnabled;
    /** [UX35] 초기/재설정 비밀번호 사용 중 — 로그인 후 변경 강제 */
    private Boolean mustChangePassword;
    private List<String> roles = new ArrayList<>();

    public boolean isOtpEnabled() {
        return Boolean.TRUE.equals(otpEnabled);
    }

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /** [B6] 해당 일자에 휴직 중인지 — loaEnd 없으면 복직 처리 전까지 휴직. */
    public boolean isOnLeave(java.time.LocalDate date) {
        return Boolean.TRUE.equals(active) && loaStart != null && date != null
                && !date.isBefore(loaStart) && (loaEnd == null || !date.isAfter(loaEnd));
    }

    public boolean isOnLeaveToday() {
        return isOnLeave(java.time.LocalDate.now());
    }

    /** [B6] 재직상태 3단계 라벨 — 퇴직(비활성) / 휴직 / 재직. */
    public String getEmploymentStatusLabel() {
        if (!Boolean.TRUE.equals(active)) {
            return "퇴직";
        }
        return isOnLeaveToday() ? "휴직" : "재직";
    }
}
