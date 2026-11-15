package com.portfolio.worksystem;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.worksystem.mapper.HrMapper;
import com.portfolio.worksystem.mapper.LoaMapper;
import com.portfolio.worksystem.mapper.CommunicationMapper;
import com.portfolio.worksystem.model.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class HrLifecycleMapperTest {
    @Container
    static final MariaDBContainer<?> database = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("hr_regression").withUsername("test").withPassword(java.util.UUID.randomUUID().toString());

    @Test
    void migrateLegacyDeductionsAndRetainLeaveEffectUntilCancellationApproval() throws Exception {
        flyway("86").migrate();
        long userId;
        long teamId;
        try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
             var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO teams(code,name) VALUES ('HR_REGRESSION','HR regression')");
            try (var rows = sql.executeQuery("SELECT LAST_INSERT_ID()")) { rows.next(); teamId = rows.getLong(1); }
            sql.executeUpdate("INSERT INTO users(username,password,name,team_id) VALUES ('hr-regression','encoded','직원'," + teamId + ")");
            try (var rows = sql.executeQuery("SELECT LAST_INSERT_ID()")) { rows.next(); userId = rows.getLong(1); }
            sql.executeUpdate("INSERT INTO leave_requests(user_id,type,start_date,end_date,days,status) VALUES ("
                    + userId + ",'ANNUAL','2025-03-03','2025-03-04',2,'CANCEL_PENDING'),("
                    + userId + ",'SICK','2025-03-05','2025-03-05',1,'PENDING')");
        }
        flyway(null).migrate();
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.mariadb.jdbc.Driver", database.getJdbcUrl(), database.getUsername(), database.getPassword())));
        config.setMapUnderscoreToCamelCase(true);
        config.getTypeAliasRegistry().registerAliases("com.portfolio.worksystem.model");
        for (String name : new String[]{"HrMapper", "LoaMapper", "CommunicationMapper"}) {
            String resource = "/mapper/" + name + ".xml";
            try (var xml = getClass().getResourceAsStream(resource)) {
                new XMLMapperBuilder(xml, config, resource, config.getSqlFragments()).parse();
            }
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            HrMapper hr = session.getMapper(HrMapper.class);
            var requests = hr.findLeaveRequests(userId, null, 2025);
            Map<String, LeaveRequest> byType = requests.stream().collect(java.util.stream.Collectors.toMap(LeaveRequest::getType, r -> r));
            assertThat(byType.get("ANNUAL").getAnnualDeductedDays()).isEqualByComparingTo("2");
            assertThat(byType.get("SICK").getAnnualDeductedDays()).isZero();
            assertThat(hr.findLeaveRequest(byType.get("ANNUAL").getId()).getAnnualDeductedDays()).isEqualByComparingTo("2");
            LocalDate day = LocalDate.of(2025, 3, 3);
            assertThat(hr.findApprovedLeaveForDate(userId, day).getStatus()).isEqualTo(LeaveStatus.CANCEL_PENDING);
            assertThat(hr.findApprovedLeavesInRange(day, day)).hasSize(1);
            assertThat(hr.findApprovedTeamLeaves(teamId, day, day)).hasSize(1);
            CommunicationMapper communication = session.getMapper(CommunicationMapper.class);
            assertThat(communication.findEmployeePresence(day).stream().filter(p -> p.getUserId().equals(userId)))
                    .singleElement().satisfies(p -> assertThat(p.getStatus()).isEqualTo("LEAVE"));
            hr.updateLeaveStatus(byType.get("ANNUAL").getId(), LeaveStatus.CANCEL_APPROVED, userId, null);
            assertThat(hr.findApprovedLeaveForDate(userId, day)).isNull();
            assertThat(hr.findApprovedLeavesInRange(day, day)).isEmpty();
            assertThat(hr.findApprovedTeamLeaves(teamId, day, day)).isEmpty();
            assertThat(communication.findEmployeePresence(day).stream().filter(p -> p.getUserId().equals(userId)))
                    .singleElement().satisfies(p -> assertThat(p.getStatus()).isNotEqualTo("LEAVE"));

            LeaveRequest newRequest = new LeaveRequest();
            newRequest.setUserId(userId);
            newRequest.setType("ANNUAL");
            newRequest.setStartDate(day);
            newRequest.setEndDate(day);
            newRequest.setDays(BigDecimal.ONE);
            newRequest.setAnnualDeductedDays(new BigDecimal("0.50"));
            newRequest.setStatus(LeaveStatus.PENDING);
            newRequest.setCurrentApprovalStep(1);
            hr.insertLeaveRequest(newRequest);
            assertThat(hr.findLeaveRequest(newRequest.getId()).getAnnualDeductedDays()).isEqualByComparingTo("0.50");

            LoaMapper loa = session.getMapper(LoaMapper.class);
            LoaRequest period = period(userId, day);
            loa.insertRequest(period);
            assertThat(loa.closeApprovedPeriod(userId, "PERSONAL", day, day.plusDays(5), userId)).isEqualTo(1);
            assertThat(loa.findRequest(period.getId()).getEndDate()).isEqualTo(day.plusDays(5));
            assertThat(loa.findOverlapping(userId, day.plusDays(6), null, null)).isNull();
            assertThat(loa.findApprovedPeriodsInYear(2025)).singleElement()
                    .satisfies(p -> assertThat(p.getEndDate()).isEqualTo(day.plusDays(5)));
            LoaRequest future = period(userId, day.plusMonths(1));
            loa.insertRequest(future);
            loa.closeApprovedPeriod(userId, "PERSONAL", future.getStartDate(), day.plusDays(5), userId);
            assertThat(loa.findRequest(future.getId()).getStatus()).isEqualTo(LoaStatus.CANCELED);
        }
    }

    private LoaRequest period(long userId, LocalDate from) {
        var period = new LoaRequest();
        period.setUserId(userId);
        period.setType("PERSONAL");
        period.setStartDate(from);
        period.setStatus(LoaStatus.APPROVED);
        period.setCurrentApprovalStep(1);
        period.setDecidedBy(userId);
        return period;
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }
}
