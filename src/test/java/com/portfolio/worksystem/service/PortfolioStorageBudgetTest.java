package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortfolioStorageBudgetTest {
    @TempDir Path root;

    @Test void generatedDocumentsAndCopiesShareUploadReservationsAndExistingUsage() throws Exception {
        var budget = new PortfolioStorageBudget(true, root.toString(), 12);
        Path document = root.resolve("approval.html");
        budget.write(document, new byte[4]);
        try (var upload = budget.reserve(5)) {
            assertThatThrownBy(() -> budget.copy(document, root.resolve("snapshot.png")))
                    .isInstanceOf(PortfolioStorageBudget.LimitExceededException.class);
        }
        budget.copy(document, root.resolve("snapshot.png"));
        budget.write(root.resolve("signature.png"), new byte[4]);
        assertThatThrownBy(() -> budget.write(root.resolve("overflow.html"), new byte[1]))
                .isInstanceOf(PortfolioStorageBudget.LimitExceededException.class);
        assertThat(Files.exists(root.resolve("overflow.html"))).isFalse();
        assertThat(Files.size(document)).isEqualTo(4);
    }

    @Test void failedGeneratedWriteReleasesSpaceAndClosingTwiceDoesNotBypassLimit() throws Exception {
        var budget = new PortfolioStorageBudget(true, root.toString(), 6);
        assertThatThrownBy(() -> budget.write(root.resolve("missing/approval.html"), new byte[6]))
                .isInstanceOf(IOException.class);
        var reservation = budget.reserve(6);
        reservation.close();
        reservation.close();
        try (var full = budget.reserve(6)) {
            assertThatThrownBy(() -> budget.reserve(1))
                    .isInstanceOf(PortfolioStorageBudget.LimitExceededException.class);
        }
        budget.write(root.resolve("approval.html"), new byte[6]);
    }

    @Test void normalModePreservesExistingWriteBehavior() throws Exception {
        var budget = new PortfolioStorageBudget(false, root.resolve("unused").toString(), 1);
        budget.write(root.resolve("document.html"), new byte[20]);
        assertThat(Files.size(root.resolve("document.html"))).isEqualTo(20);
        try (var ignored = budget.reserve(-1)) {
            budget.copy(root.resolve("document.html"), root.resolve("copy.html"));
        }
        assertThat(Files.size(root.resolve("copy.html"))).isEqualTo(20);
    }
}
