package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.worksystem.service.PortfolioStorageBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PortfolioStorageBudgetFilterTest {
    @TempDir Path root;

    @Test void rejectsUnknownLengthBeforeBodyOrParameterParsing() throws Exception {
        var filter = filter(true, 10);
        var request = new MockHttpServletRequest("POST", "/projects/1/tasks/2/evidence") {
            @Override public String getParameter(String name) { throw new AssertionError("Body was parsed"); }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Body was read"); }
        };
        request.setContentType("multipart/form-data; boundary=demo");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Handler was called"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("파일 크기를 확인할 수 없어");
        assertThat(PortfolioStorageBudgetFilter.class.getAnnotation(Order.class).value())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Test void concurrentUploadsReserveSpaceUntilWrittenAndRejectOverflow() throws Exception {
        Files.write(root.resolve("existing.bin"), new byte[2]);
        var filter = filter(true, 10);
        var entered = new CountDownLatch(1);
        var complete = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> {
                filter.doFilter(multipart(6), new MockHttpServletResponse(), (req, res) -> {
                    entered.countDown();
                    try {
                        if (!complete.await(5, TimeUnit.SECONDS)) throw new AssertionError("Upload was not released");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    Files.write(root.resolve("first.bin"), new byte[6]);
                });
                return null;
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertRejected(filter, 4);
            } finally {
                complete.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertRejected(filter, 4);
            var accepted = new AtomicBoolean();
            filter.doFilter(multipart(2), new MockHttpServletResponse(), (req, res) -> {
                Files.write(root.resolve("last.bin"), new byte[2]);
                accepted.set(true);
            });
            assertThat(accepted).isTrue();
            assertRejected(filter, 1);
            assertThat(Files.size(root.resolve("existing.bin"))).isEqualTo(2);
        }
    }

    @Test void failureReleasesReservationAndDoesNotDeleteExistingFiles() throws Exception {
        Files.write(root.resolve("existing.bin"), new byte[2]);
        var filter = filter(true, 10);
        assertThatThrownBy(() -> filter.doFilter(multipart(7), new MockHttpServletResponse(),
                (req, res) -> { throw new IOException("upload failed"); })).isInstanceOf(IOException.class);
        var accepted = new AtomicBoolean();
        filter.doFilter(multipart(8), new MockHttpServletResponse(), (req, res) -> accepted.set(true));
        assertThat(accepted).isTrue();
        assertThat(Files.size(root.resolve("existing.bin"))).isEqualTo(2);
    }

    @Test void normalModeAndNonMultipartEditingRemainUsable() throws Exception {
        var unknownLength = new MockHttpServletRequest("POST", "/projects/1/tasks/2/evidence");
        unknownLength.setContentType("multipart/form-data");
        var accepted = new AtomicBoolean();
        filter(false, 1).doFilter(unknownLength, new MockHttpServletResponse(), (req, res) -> accepted.set(true));
        assertThat(accepted).isTrue();
        Files.write(root.resolve("full.bin"), new byte[2]);
        accepted.set(false);
        var edit = new MockHttpServletRequest("POST", "/projects/1/tasks/2");
        edit.setContentType("application/x-www-form-urlencoded");
        filter(true, 1).doFilter(edit, new MockHttpServletResponse(), (req, res) -> accepted.set(true));
        assertThat(accepted).isTrue();
    }

    @Test void unreadableOrInvalidRootFailsClosed() throws Exception {
        Path file = root.resolve("not-a-directory");
        Files.writeString(file, "existing");
        var filter = new PortfolioStorageBudgetFilter(new PortfolioStorageBudget(true, file.toString(), 10));
        var response = new MockHttpServletResponse();
        filter.doFilter(multipart(1), response, (req, res) -> { throw new AssertionError("Handler was called"); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("저장 공간을 확인하지 못했습니다");
    }

    private PortfolioStorageBudgetFilter filter(boolean enabled, long maximum) {
        return new PortfolioStorageBudgetFilter(new PortfolioStorageBudget(enabled, root.toString(), maximum));
    }

    private static MockHttpServletRequest multipart(int bytes) {
        var request = new MockHttpServletRequest("POST", "/projects/1/tasks/2/evidence");
        request.setContentType("multipart/form-data; boundary=demo");
        request.setContent(new byte[bytes]);
        return request;
    }

    private static void assertRejected(PortfolioStorageBudgetFilter filter, int bytes) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(multipart(bytes), response, (req, res) -> { throw new AssertionError("Handler was called"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("저장 공간이 가득");
    }
}
