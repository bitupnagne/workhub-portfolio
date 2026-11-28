package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PublicDemoMigrationBoundaryTest {
    @Test void wrongDatabaseCannotMigrateOrRepair() throws Exception {
        Flyway flyway = mock(Flyway.class, RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(flyway.getConfiguration().getDataSource().getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("unrelated");
        var strategy = new PublicDemoMigrationConfig().publicDemoMigrationStrategy(true, java.util.UUID.randomUUID().toString());
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).migrate();
        verify(flyway, never()).repair();
        verify(connection).close();
    }

    @Test void missingCredentialsFailBeforeAnyDatabaseConnection() {
        Flyway flyway = mock(Flyway.class);
        var strategy = new PublicDemoMigrationConfig().publicDemoMigrationStrategy(true, "");
        assertThatThrownBy(() -> strategy.migrate(flyway)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(flyway);
    }

    @Test void isolatedDatabaseMigratesWithoutRepair() throws Exception {
        Flyway flyway = mock(Flyway.class, RETURNS_DEEP_STUBS);
        Connection connection = mock(Connection.class);
        when(flyway.getConfiguration().getDataSource().getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("workhub_demo");
        new PublicDemoMigrationConfig().publicDemoMigrationStrategy(true, java.util.UUID.randomUUID().toString()).migrate(flyway);
        verify(flyway).migrate();
        verify(flyway, never()).repair();
        verify(connection).close();
    }
}
