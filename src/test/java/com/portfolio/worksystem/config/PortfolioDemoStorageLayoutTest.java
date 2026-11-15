package com.portfolio.worksystem.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class PortfolioDemoStorageLayoutTest {
    @TempDir Path directory;

    @Test void missingUploadSettingFailsClosedOnlyInDemoMode() {
        var environment = new MockEnvironment();
        assertThatCode(() -> new PortfolioDemoStorageLayout(false, directory.toString(), environment)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new PortfolioDemoStorageLayout(true, directory.toString(), environment))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void allUploadPathsInsideTheBudgetRootAreAccepted() {
        assertThatCode(() -> new PortfolioDemoStorageLayout(true, directory.toString(), configured()))
                .doesNotThrowAnyException();
    }

    @Test void outsidePathOrParentTraversalIsRejected() {
        var environment = configured().withProperty("app.upload.message-attachment-dir", directory.resolve("../outside").toString());
        assertThatThrownBy(() -> new PortfolioDemoStorageLayout(true, directory.toString(), environment))
                .isInstanceOf(IllegalStateException.class);
    }

    private MockEnvironment configured() {
        var environment = new MockEnvironment();
        for (String property : PortfolioDemoStorageLayout.UPLOAD_PROPERTIES) {
            environment.withProperty(property, directory.resolve(property.substring(property.lastIndexOf('.') + 1)).toString());
        }
        return environment;
    }
}
