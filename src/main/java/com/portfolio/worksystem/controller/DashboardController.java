package com.portfolio.worksystem.controller;

import com.portfolio.worksystem.model.AttendanceDailyStatus;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.BusinessTrip;
import com.portfolio.worksystem.model.BusinessTripStatus;
import com.portfolio.worksystem.model.LeaveRequest;
import com.portfolio.worksystem.model.LeaveStatus;
import com.portfolio.worksystem.model.ReportItem;
import com.portfolio.worksystem.model.Team;
import com.portfolio.worksystem.model.TeamWeeklyStatus;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.model.WeeklyReport;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.service.CommunicationService;
import com.portfolio.worksystem.service.HrService;
import com.portfolio.worksystem.service.OrganizationService;
import com.portfolio.worksystem.service.ReportService;
import com.portfolio.worksystem.service.WeekUtils;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequiredArgsConstructor
@Controller
public class DashboardController {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TODAY_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd EEEE", Locale.KOREAN);

    private final OrganizationService organizationService;
    private final ReportService reportService;
    private final HrService hrService;
    private final CommunicationService communicationService;

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal CustomUserDetails user,
                            @RequestParam(defaultValue = "true") boolean includeContractors,
                            @RequestParam(required = false) String month,
                            Model model) {
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        String weekKey = reportService.defaultWeekKey();
        List<UserAccount> scopedUsers = organizationService.findUsers(user);
        long contractorCount = scopedUsers.stream().filter(employee -> Boolean.TRUE.equals(employee.getContractor())).count();
        List<UserAccount> dashboardUsers = includeContractors ? scopedUsers : scopedUsers.stream()
                .filter(employee -> !Boolean.TRUE.equals(employee.getContractor()))
                .toList();
        Set<Long> dashboardUserIds = dashboardUsers.stream().map(UserAccount::getId).collect(Collectors.toSet());
        List<AttendanceDailyStatus> todayStatuses = hrService.allAttendanceDailyStatuses(today, today).stream()
                .filter(status -> dashboardUserIds.contains(status.getUserId()))
                .toList();
        List<LeaveRequest> todayLeaves = hrService.allLeaveRequests(today.getYear()).stream()
                .filter(request -> dashboardUserIds.contains(request.getUserId()))
                .filter(request -> request.getStatus() == LeaveStatus.APPROVED || request.getStatus() == LeaveStatus.CANCEL_PENDING)
                .filter(request -> containsDate(request.getStartDate(), request.getEndDate(), today))
                .toList();
        List<BusinessTrip> todayTrips = hrService.allBusinessTrips(today.getYear()).stream()
                .filter(trip -> dashboardUserIds.contains(trip.getUserId()))
                .filter(trip -> trip.getStatus() == BusinessTripStatus.APPROVED)
                .filter(trip -> containsDate(trip.getStartDate(), trip.getEndDate(), today))
                .toList();
        YearMonth calendarMonth = normalizeCalendarMonth(month, today);
        LocalDate monthStart = calendarMonth.atDay(1);
        LocalDate monthEnd = calendarMonth.atEndOfMonth();
        List<CalendarDay> calendarDays = dashboardCalendar(monthStart, monthEnd);
        List<ReportItem> myItems = reportService.myFlatItems(user.getUserId(), weekKey);
        WeeklyReport myReport = reportService.findMyReport(user.getUserId(), weekKey);
        boolean reportSubmitted = myReport != null && Boolean.TRUE.equals(myReport.getSubmitted());
        int reportProgress = reportSubmitted ? 100 : Math.min(90, myItems.size() * 20);
        long workingTodayCount = todayStatuses.stream()
                .filter(status -> isWorking(status.getStatus()))
                .count();
        long scheduledTodayCount = countStatus(todayStatuses, AttendanceStatus.SCHEDULED);
        long absentTodayCount = countStatus(todayStatuses, AttendanceStatus.ABSENT);
        long outsideTodayCount = countStatus(todayStatuses, AttendanceStatus.OUTSIDE);
        long leaveTodayCount = todayLeaves.stream().map(LeaveRequest::getUserId).distinct().count();
        long tripTodayCount = todayTrips.stream().map(BusinessTrip::getUserId).distinct().count();
        long unavailableTodayCount = leaveTodayCount + tripTodayCount + absentTodayCount;
        int attendanceRate = dashboardUsers.isEmpty() ? 0 : Math.toIntExact(Math.round(workingTodayCount * 100.0 / dashboardUsers.size()));

        addCommon(model, user, "대시보드", "dashboard");
        model.addAttribute("today", today);
        model.addAttribute("todayLabel", today.format(TODAY_FORMAT));
        model.addAttribute("weekKey", weekKey);
        model.addAttribute("weekLabel", WeekUtils.getWeekLabel(weekKey));
        model.addAttribute("weekPeriod", WeekUtils.getWeekPeriod(weekKey));
        model.addAttribute("weeklyReportDueToday", today.getDayOfWeek() == DayOfWeek.FRIDAY);
        model.addAttribute("totalEmployees", dashboardUsers.size());
        model.addAttribute("regularEmployeeCount", scopedUsers.size() - contractorCount);
        model.addAttribute("contractorCount", contractorCount);
        model.addAttribute("includeContractors", includeContractors);
        model.addAttribute("workingTodayCount", workingTodayCount);
        model.addAttribute("scheduledTodayCount", scheduledTodayCount);
        model.addAttribute("tripTodayCount", tripTodayCount);
        model.addAttribute("leaveTodayCount", leaveTodayCount);
        model.addAttribute("absentTodayCount", absentTodayCount);
        model.addAttribute("outsideTodayCount", outsideTodayCount);
        model.addAttribute("unavailableTodayCount", unavailableTodayCount);
        model.addAttribute("attendanceRate", attendanceRate);
        model.addAttribute("reportSubmitted", reportSubmitted);
        model.addAttribute("reportProgress", reportProgress);
        model.addAttribute("myReportItemCount", myItems.size());
        model.addAttribute("weekConfirmed", reportService.isWeekConfirmed(weekKey));
        model.addAttribute("unreadNotificationCount", communicationService.unreadNotificationCount(user));
        model.addAttribute("notifications", communicationService.notifications(user).stream().limit(5).toList());
        model.addAttribute("noticeFeed", communicationService.noticeFeed(user).stream().limit(5).toList());
        model.addAttribute("todayLeaves", todayLeaves.stream().limit(5).toList());
        model.addAttribute("todayTrips", todayTrips.stream().limit(5).toList());
        model.addAttribute("dashboardCalendarDays", calendarDays);
        model.addAttribute("dashboardSchedules", hrService.companySchedules(monthStart, monthEnd));
        model.addAttribute("dashboardHolidays", hrService.companyHolidays(monthStart, monthEnd));
        model.addAttribute("dashboardMonthLabel", calendarMonth.getYear() + "년 " + calendarMonth.getMonthValue() + "월");
        model.addAttribute("dashboardMonthValue", calendarMonth.toString());
        model.addAttribute("dashboardPrevMonth", calendarMonth.minusMonths(1).toString());
        model.addAttribute("dashboardNextMonth", calendarMonth.plusMonths(1).toString());
        model.addAttribute("dashboardTodayMonth", YearMonth.from(today).toString());
        model.addAttribute("dashboardCurrentMonth", YearMonth.from(today).equals(calendarMonth));
        model.addAttribute("teams", organizationService.findTeams());
        model.addAttribute("collectedTeamCount", collectedTeamCount(user, weekKey));
        model.addAttribute("teamReportSubmitted", teamReportSubmitted(user, weekKey));
        return "dashboard";
    }

    private void addCommon(Model model, CustomUserDetails user, String title, String active) {
        Team reportAdminTeam = organizationService.getReportAdminTeam();
        model.addAttribute("currentUser", user);
        model.addAttribute("title", title);
        model.addAttribute("active", active);
        model.addAttribute("reportAdminTeam", reportAdminTeam);
        model.addAttribute("canManageWeeklyReport", organizationService.canManageWeeklyReport(user));
        model.addAttribute("canManageOrganization", organizationService.canManageOrganization(user));
    }

    private boolean containsDate(LocalDate startDate, LocalDate endDate, LocalDate targetDate) {
        return startDate != null
                && endDate != null
                && !targetDate.isBefore(startDate)
                && !targetDate.isAfter(endDate);
    }

    private boolean isWorking(AttendanceStatus status) {
        return status == AttendanceStatus.NORMAL
                || status == AttendanceStatus.LATE
                || status == AttendanceStatus.EARLY_LEAVE
                || status == AttendanceStatus.REMOTE
                || status == AttendanceStatus.OUTSIDE;
    }

    private long countStatus(List<AttendanceDailyStatus> statuses, AttendanceStatus status) {
        return statuses.stream().filter(row -> row.getStatus() == status).count();
    }

    private List<CalendarDay> dashboardCalendar(LocalDate monthStart, LocalDate monthEnd) {
        LocalDate start = monthStart.minusDays((monthStart.getDayOfWeek().getValue() + 6) % 7);
        LocalDate end = monthEnd.plusDays(7 - monthEnd.getDayOfWeek().getValue());
        List<CalendarDay> days = new ArrayList<>();
        LocalDate today = LocalDate.now(SERVICE_ZONE);
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            days.add(new CalendarDay(cursor, cursor.getMonth() == monthStart.getMonth(), cursor.equals(today)));
        }
        return days;
    }

    private YearMonth normalizeCalendarMonth(String month, LocalDate today) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(today);
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (RuntimeException e) {
            return YearMonth.from(today);
        }
    }

    private int collectedTeamCount(CustomUserDetails user, String weekKey) {
        return organizationService.canManageWeeklyReport(user) ? reportService.adminItems(weekKey).size() : 0;
    }

    private boolean teamReportSubmitted(CustomUserDetails user, String weekKey) {
        if (user == null || !user.isTeamLeader()) {
            return false;
        }
        TeamWeeklyStatus status = reportService.teamStatus(user.getTeamId(), weekKey);
        return status != null && Boolean.TRUE.equals(status.getSubmitted());
    }

    public record CalendarDay(LocalDate date, boolean inMonth, boolean today) {
        public boolean saturday() {
            return date != null && date.getDayOfWeek() == DayOfWeek.SATURDAY;
        }

        public boolean sunday() {
            return date != null && date.getDayOfWeek() == DayOfWeek.SUNDAY;
        }
    }
}
