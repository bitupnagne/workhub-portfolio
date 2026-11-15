package com.portfolio.worksystem.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Refuse a wrong public-demo database before Flyway can modify any schema or data. */
@Configuration
public class PublicDemoMigrationConfig {
    @Bean FlywayMigrationStrategy publicDemoMigrationStrategy(
            @Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}") boolean publicDemo,
            @Value("${app.portfolio.demo-password:${PORTFOLIO_DEMO_PASSWORD:}}") String password) {
        return flyway -> {
            if (publicDemo) {
                if (password == null || password.isBlank() || password.length() < 12) {
                    throw new IllegalStateException("공개 데모 비밀번호를 외부 설정으로 12자 이상 제공해 주세요.");
                }
                try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                    if (!"workhub_demo".equals(connection.getCatalog())) {
                        throw new IllegalStateException("공개 데모는 별도의 전용 데이터베이스에서만 시작할 수 있습니다.");
                    }
                } catch (java.sql.SQLException error) {
                    throw new IllegalStateException("공개 데모 전용 데이터베이스를 확인할 수 없습니다.");
                }
            }
            flyway.migrate();
        };
    }
}
