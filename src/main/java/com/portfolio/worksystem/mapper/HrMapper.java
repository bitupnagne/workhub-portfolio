package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.AttendanceCorrection;
import com.portfolio.worksystem.model.AttendanceCorrectionStatus;
import com.portfolio.worksystem.model.AttendanceDayOverride;
import com.portfolio.worksystem.model.AttendanceRecord;
import com.portfolio.worksystem.model.AttendanceSetting;
import com.portfolio.worksystem.model.AttendanceWeekDay;
import com.portfolio.worksystem.model.AttendanceStatus;
import com.portfolio.worksystem.model.BusinessTrip;
import com.portfolio.worksystem.model.BusinessTripExpense;
import com.portfolio.worksystem.model.BusinessTripReceipt;
import com.portfolio.worksystem.model.BusinessTripStatus;
import com.portfolio.worksystem.model.CompanyHoliday;
import com.portfolio.worksystem.model.CompanySchedule;
import com.portfolio.worksystem.model.FlexTimeRequest;
import com.portfolio.worksystem.model.FlexTimeStatus;
import com.portfolio.worksystem.model.LeaveBalance;
import com.portfolio.worksystem.model.LeaveRequest;
import com.portfolio.worksystem.model.LeaveStatus;
import com.portfolio.worksystem.model.ShiftGroupDef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HrMapper {
    LeaveBalance findLeaveBalance(@Param("userId") Long userId, @Param("year") Integer year);
    void insertLeaveBalance(LeaveBalance balance);
    void upsertLeaveBalance(LeaveBalance balance);
    void increaseUsedLeave(@Param("userId") Long userId, @Param("year") Integer year, @Param("days") BigDecimal days);
    List<LeaveRequest> findLeaveRequests(@Param("userId") Long userId, @Param("teamId") Long teamId, @Param("year") Integer year);
    LeaveRequest findLeaveRequest(Long id);
    LeaveRequest findApprovedLeaveForDate(@Param("userId") Long userId, @Param("workDate") LocalDate workDate);
    List<LeaveRequest> findApprovedLeavesInRange(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    void insertLeaveRequest(LeaveRequest request);
    void updateLeaveStatus(@Param("id") Long id, @Param("status") LeaveStatus status, @Param("approverId") Long approverId,
                           @Param("rejectedReason") String rejectedReason);
    void updateLeaveCancelReason(@Param("id") Long id, @Param("cancelReason") String cancelReason);
    void updateLeaveCurrentStep(@Param("id") Long id, @Param("currentApprovalStep") Integer currentApprovalStep);

    List<AttendanceWeekDay> findWeekSchedule();
    void upsertWeekDay(AttendanceWeekDay day);

    List<ShiftGroupDef> findShiftGroups();
    ShiftGroupDef findShiftGroupByCode(String code);
    void insertShiftGroup(ShiftGroupDef group);
    void updateShiftGroup(ShiftGroupDef group);

    AttendanceSetting findAttendanceSetting();
    void updateAttendanceSetting(@Param("defaultStartTime") LocalTime defaultStartTime,
                                 @Param("defaultEndTime") LocalTime defaultEndTime,
                                 @Param("updatedBy") Long updatedBy);
    List<AttendanceDayOverride> findAttendanceOverrides(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    AttendanceDayOverride findAttendanceOverride(LocalDate workDate);
    void upsertAttendanceOverride(AttendanceDayOverride override);
    void deleteAttendanceOverride(LocalDate workDate);
    List<CompanyHoliday> findCompanyHolidays(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    CompanyHoliday findCompanyHoliday(LocalDate holidayDate);
    void upsertCompanyHoliday(CompanyHoliday holiday);
    void deleteCompanyHoliday(LocalDate holidayDate);
    List<CompanySchedule> findCompanySchedules(@Param("fromAt") LocalDateTime fromAt, @Param("toAt") LocalDateTime toAt);
    void insertCompanySchedule(CompanySchedule schedule);
    void updateCompanySchedule(CompanySchedule schedule);
    void deleteCompanySchedule(@Param("id") Long id);

    List<AttendanceRecord> findAttendanceRecords(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                                  @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    AttendanceRecord findAttendanceByUserDate(@Param("userId") Long userId, @Param("workDate") LocalDate workDate);
    /** [UX18] 미퇴근(check_out NULL) 상태의 가장 최근 출근 기록 — 자정 넘긴 야근 퇴근용. */
    AttendanceRecord findLatestOpenAttendance(@Param("userId") Long userId);
    void insertAttendance(AttendanceRecord record);
    void updateAttendanceCheckOut(@Param("id") Long id, @Param("checkOutAt") LocalDateTime checkOutAt,
                                  @Param("status") AttendanceStatus status);
    void updateAttendanceRecord(@Param("id") Long id, @Param("checkInAt") LocalDateTime checkInAt,
                                @Param("checkOutAt") LocalDateTime checkOutAt, @Param("status") AttendanceStatus status);

    void insertAttendanceAdminAdjustment(com.portfolio.worksystem.model.AttendanceAdminAdjustment adjustment);

    com.portfolio.worksystem.model.LeaveAccrualPolicy findLeaveAccrualPolicy();
    void updateLeaveAccrualPolicy(com.portfolio.worksystem.model.LeaveAccrualPolicy policy);
    List<LeaveBalance> findLeaveBalancesFromYear(@Param("year") Integer year);
    void updateLeaveBalanceTotal(@Param("id") Long id, @Param("totalDays") java.math.BigDecimal totalDays);

    List<com.portfolio.worksystem.model.LeaveTypeDef> findLeaveTypeDefs();
    com.portfolio.worksystem.model.LeaveTypeDef findLeaveTypeDef(@Param("code") String code);
    void insertLeaveTypeDef(com.portfolio.worksystem.model.LeaveTypeDef def);
    void updateLeaveTypeDef(com.portfolio.worksystem.model.LeaveTypeDef def);

    void insertMonthLockEvent(com.portfolio.worksystem.model.AttendanceMonthLockEvent event);
    com.portfolio.worksystem.model.AttendanceMonthLockEvent findLatestMonthLockEvent(@Param("ym") String ym);
    List<String> findLockedMonths();
    List<com.portfolio.worksystem.model.AttendanceMonthLockEvent> findMonthLockEvents();

    List<LeaveRequest> findApprovedTeamLeaves(@Param("teamId") Long teamId,
                                              @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    List<LeaveBalance> findLeaveBalancesByYear(@Param("year") Integer year);
    void increaseAdjustedLeave(@Param("userId") Long userId, @Param("year") Integer year,
                               @Param("delta") java.math.BigDecimal delta);
    void insertLeaveBalanceAdjustment(com.portfolio.worksystem.model.LeaveBalanceAdjustment adjustment);
    List<com.portfolio.worksystem.model.LeaveBalanceAdjustment> findLeaveBalanceAdjustments(
            @Param("userId") Long userId, @Param("year") Integer year);

    void insertAttendanceCorrection(AttendanceCorrection correction);
    List<AttendanceCorrection> findAttendanceCorrections(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                                          @Param("year") Integer year);
    AttendanceCorrection findAttendanceCorrection(Long id);
    List<AttendanceCorrection> findPendingCorrections(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                                      @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    boolean existsPendingCorrection(@Param("userId") Long userId, @Param("workDate") LocalDate workDate);
    void updateAttendanceCorrectionStatus(@Param("id") Long id, @Param("status") AttendanceCorrectionStatus status,
                                          @Param("approverId") Long approverId, @Param("rejectedReason") String rejectedReason);
    void updateAttendanceCorrectionCurrentStep(@Param("id") Long id, @Param("currentApprovalStep") Integer currentApprovalStep);

    List<FlexTimeRequest> findApprovedFlexTimes(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                                @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    FlexTimeRequest findApprovedFlexTimeForDate(@Param("userId") Long userId, @Param("workDate") LocalDate workDate);
    void insertFlexTimeRequest(FlexTimeRequest request);
    List<FlexTimeRequest> findFlexTimeRequests(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                                @Param("year") Integer year);
    FlexTimeRequest findFlexTimeRequest(Long id);
    boolean existsOverlappingFlexTime(@Param("userId") Long userId, @Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);
    void updateFlexTimeStatus(@Param("id") Long id, @Param("status") FlexTimeStatus status,
                              @Param("approverId") Long approverId, @Param("rejectedReason") String rejectedReason);
    void updateFlexTimeCurrentStep(@Param("id") Long id, @Param("currentApprovalStep") Integer currentApprovalStep);

    List<BusinessTrip> findBusinessTrips(@Param("userId") Long userId, @Param("teamId") Long teamId, @Param("year") Integer year);
    List<BusinessTrip> findApprovedBusinessTrips(@Param("userId") Long userId, @Param("teamId") Long teamId,
                                                  @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);
    BusinessTrip findBusinessTrip(Long id);
    void insertBusinessTrip(BusinessTrip trip);
    void insertBusinessTripExpense(BusinessTripExpense expense);
    List<BusinessTripExpense> findBusinessTripExpenses(Long tripId);
    void insertBusinessTripReceipt(BusinessTripReceipt receipt);
    BusinessTripReceipt findBusinessTripReceipt(Long id);
    BusinessTripReceipt findBusinessTripReceiptByTripId(Long tripId);
    void updateBusinessTripStatus(@Param("id") Long id, @Param("status") BusinessTripStatus status,
                                  @Param("approverId") Long approverId, @Param("rejectedReason") String rejectedReason);
    void updateBusinessTripCurrentStep(@Param("id") Long id, @Param("currentApprovalStep") Integer currentApprovalStep);

}
