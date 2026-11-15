package com.portfolio.worksystem.mapper;

import com.portfolio.worksystem.model.BusinessTripReport;
import com.portfolio.worksystem.model.BusinessTripReportExpense;
import com.portfolio.worksystem.model.BusinessTripReportFile;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** [UX47] 출장보고서. */
@Mapper
public interface TripReportMapper {
    void insertReport(BusinessTripReport report);
    BusinessTripReport findById(Long id);
    BusinessTripReport findByTripId(Long tripId);
    List<BusinessTripReport> findByTripIds(@Param("tripIds") List<Long> tripIds);
    void updateStatus(@Param("id") Long id, @Param("status") String status,
                      @Param("decidedBy") Long decidedBy, @Param("rejectedReason") String rejectedReason);
    void updateCurrentStep(@Param("id") Long id, @Param("stepOrder") int stepOrder);
    void deleteReport(Long id);

    void insertExpense(BusinessTripReportExpense expense);
    List<BusinessTripReportExpense> findExpenses(Long reportId);
    void deleteExpenses(Long reportId);

    void insertFile(BusinessTripReportFile file);
    List<BusinessTripReportFile> findFiles(Long reportId);
    BusinessTripReportFile findFileById(Long id);
    void deleteFiles(Long reportId);
}
