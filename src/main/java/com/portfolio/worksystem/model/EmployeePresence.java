package com.portfolio.worksystem.model;

import lombok.Data;

@Data
public class EmployeePresence {
    private Long userId;
    private String name;
    private Long teamId;
    private String teamCode;
    private String teamName;
    private String dept;
    private String extensionNumber;
    private String mobileNumber;
    private String jobTitle;
    private String email;
    private String profileImageFilename;
    private String status;
    private String statusLabel;
    private String statusClass;
    private String orgGroupName;
    private String orgRole;
    private boolean online;
    /** [PR] 본인이 고른 '자리비움'(접속 중일 때만 표시). */
    private boolean away;
    private boolean contractor;
}
