package com.portfolio.worksystem.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Every public-demo upload must remain inside the directory counted by its quota. */
@Component
public final class PortfolioDemoStorageLayout {
    static final List<String> UPLOAD_PROPERTIES = List.of(
            "app.upload.profile-dir", "app.upload.signature-dir", "app.upload.notice-dir",
            "app.upload.project-attachment-dir", "app.upload.message-attachment-dir",
            "app.upload.release-dir", "app.upload.approval-document-dir",
            "app.upload.trip-receipt-dir", "app.upload.logo-dir");

    public PortfolioDemoStorageLayout(
            @Value("${app.portfolio.public-demo:${PORTFOLIO_PUBLIC_DEMO:false}}") boolean enabled,
            @Value("${app.portfolio.storage-root:${PORTFOLIO_STORAGE_ROOT:/app/data}}") String root,
            Environment environment) {
        if (!enabled) return;
        Path storage = Path.of(root).toAbsolutePath().normalize();
        for (String property : UPLOAD_PROPERTIES) {
            String configured = environment.getProperty(property);
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException("공개 데모 업로드 디렉터리를 명시적으로 설정해 주세요: " + property);
            }
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            if (!directory.startsWith(storage)) {
                throw new IllegalStateException("공개 데모 업로드 디렉터리는 저장 한도 root 안에 있어야 합니다: " + property);
            }
            for (Path parent = directory; parent != null; parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) {
                    throw new IllegalStateException("공개 데모 업로드 디렉터리는 심볼릭 링크를 사용할 수 없습니다.");
                }
            }
        }
    }
}
