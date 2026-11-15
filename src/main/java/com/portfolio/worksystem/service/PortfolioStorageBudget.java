package com.portfolio.worksystem.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 공개 데모의 실제 파일과 진행 중 저장을 함께 계산한다. 기존 파일은 삭제하지 않는다. */
@Component
public class PortfolioStorageBudget {
    public static final String LIMIT_MESSAGE = "공개 데모의 파일 저장 공간이 가득 찼습니다. 파일 없이 업무를 작성하거나 운영자에게 문의해 주세요.";
    public static final String LENGTH_MESSAGE = "파일 크기를 확인할 수 없어 업로드하지 못했습니다. 크기를 확인할 수 있는 파일로 다시 시도해 주세요.";
    private final boolean enabled;
    private final Path root;
    private final long maximumBytes;
    private long reservedBytes;

    public PortfolioStorageBudget(
            @Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}") boolean enabled,
            @Value("${app.portfolio.storage-root:${PORTFOLIO_STORAGE_ROOT:/app/data}}") String root,
            @Value("${app.portfolio.storage-max-bytes:${PORTFOLIO_STORAGE_MAX_BYTES:268435456}}") long maximumBytes) {
        this.enabled = enabled;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.maximumBytes = maximumBytes;
        if (enabled && maximumBytes <= 0) throw new IllegalArgumentException("공개 데모 저장 용량은 0보다 커야 합니다.");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized Reservation reserve(long bytes) throws IOException {
        if (!enabled) return new Reservation(null, 0);
        if (bytes < 0) throw new LimitExceededException(LENGTH_MESSAGE);
        long used = currentBytes();
        if (used > maximumBytes || bytes > maximumBytes - used
                || reservedBytes > maximumBytes - used - bytes) {
            throw new LimitExceededException(LIMIT_MESSAGE);
        }
        reservedBytes += bytes;
        return new Reservation(this, bytes);
    }

    private long currentBytes() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid demo storage root");
        long total = 0;
        // walk는 심볼릭 링크 디렉터리를 따라가지 않으며 링크 파일도 합산하지 않는다.
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    long size = Files.size(path);
                    if (size > maximumBytes - total) throw new LimitExceededException(LIMIT_MESSAGE);
                    total += size;
                }
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return total;
    }

    public void write(Path target, byte[] content) throws IOException {
        validateTarget(target);
        try (var reservation = reserve(content.length)) {
            Files.write(target, content);
        }
    }

    public void copy(Path source, Path target) throws IOException {
        validateTarget(target);
        try (var reservation = reserve(Files.size(source))) {
            Files.copy(source, target);
        }
    }

    private void validateTarget(Path target) throws IOException {
        if (enabled && !target.toAbsolutePath().normalize().startsWith(root)) {
            throw new IOException("File target is outside the demo storage root");
        }
    }

    private synchronized void release(Reservation reservation) {
        if (reservation.closed) return;
        reservedBytes -= reservation.bytes;
        reservation.closed = true;
    }

    public static final class Reservation implements AutoCloseable {
        private final PortfolioStorageBudget owner;
        private final long bytes;
        private boolean closed;

        private Reservation(PortfolioStorageBudget owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        @Override public void close() {
            if (owner != null) owner.release(this);
        }
    }

    public static final class LimitExceededException extends RuntimeException {
        public LimitExceededException(String message) {
            super(message);
        }
    }
}
