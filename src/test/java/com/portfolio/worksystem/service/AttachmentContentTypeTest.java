package com.portfolio.worksystem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.worksystem.mapper.CommunicationMapper;
import com.portfolio.worksystem.mapper.UserMapper;
import com.portfolio.worksystem.model.MessageAttachment;
import com.portfolio.worksystem.model.UserAccount;
import com.portfolio.worksystem.security.CustomUserDetails;
import com.portfolio.worksystem.websocket.RealtimeHub;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 첨부파일 Content-Type 을 <b>서버가 정한다</b>는 것을 고정한다.
 *
 * <p>다운로드는 이미지로 분류된 첨부를 {@code inline} 으로, 저장된 Content-Type 그대로
 * 서빙한다. 그래서 Content-Type 을 클라이언트가 정할 수 있으면 다음이 성립했다:
 *
 * <ol>
 *   <li>내용이 HTML 인 파일을 {@code x.png} 로 올린다 — 확장자 화이트리스트는 통과</li>
 *   <li>multipart 파트의 Content-Type 을 {@code text/html} 로 보낸다</li>
 *   <li>이미지로 분류되어 {@code inline} + {@code text/html} 로 서빙된다</li>
 *   <li>오피스 오리진에서 HTML 이 렌더링된다 — CSP 가 script-src 'unsafe-inline' 이라 스크립트도 실행</li>
 * </ol>
 *
 * <p>{@code X-Content-Type-Options: nosniff} 는 이 경우 도움이 되지 않는다 —
 * 서버가 {@code text/html} 이라고 <b>명시</b>했기 때문이다. nosniff 는 브라우저가 타입을
 * <b>추측</b>하는 것만 막는다.
 */
class AttachmentContentTypeTest {

    private final CommunicationMapper communicationMapper = mock(CommunicationMapper.class);

    private final CommunicationService service = new CommunicationService(
            communicationMapper,
            mock(UserMapper.class),
            mock(RealtimeHub.class),
            mock(OrganizationService.class),
            mock(AnonymityService.class),
            mock(PresenceCacheService.class),
            mock(SystemSettingsService.class));

    private final CustomUserDetails viewer = viewer();

    /*
      핵심 회귀 — 이 단언이 깨지면 저장형 XSS 가 되살아난다.
    */
    @Test
    @DisplayName("저장된 text/html 이라도 확장자 기준으로 다시 정한다")
    void doesNotTrustAStoredHtmlContentType() {
        stored("x.png", "text/html", "IMAGE");

        MessageAttachment served = service.messageAttachment(viewer, 1L);

        assertThat(served.getContentType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("알 수 없는 확장자는 octet-stream 으로 내려 브라우저가 렌더링하지 않게 한다")
    void unknownExtensionsFallBackToOctetStream() {
        stored("payload.svg", "image/svg+xml", "IMAGE");

        MessageAttachment served = service.messageAttachment(viewer, 1L);

        assertThat(served.getContentType()).isEqualTo("application/octet-stream");
        // IMAGE 가 아니어야 다운로드가 inline 이 아닌 attachment 로 나간다.
        assertThat(served.getAttachmentType()).isEqualTo("FILE");
    }

    @Test
    @DisplayName("확장자가 없으면 octet-stream")
    void noExtensionFallsBackToOctetStream() {
        stored("noext", "text/html", "IMAGE");

        MessageAttachment served = service.messageAttachment(viewer, 1L);

        assertThat(served.getContentType()).isEqualTo("application/octet-stream");
        assertThat(served.getAttachmentType()).isEqualTo("FILE");
    }

    @Test
    @DisplayName("정상 이미지는 그대로 이미지로 서빙한다 — 기능이 죽으면 안 된다")
    void realImagesStillServeAsImages() {
        stored("photo.JPG", "image/jpeg", "IMAGE");

        MessageAttachment served = service.messageAttachment(viewer, 1L);

        assertThat(served.getContentType()).isEqualTo("image/jpeg");
        assertThat(served.getAttachmentType()).isEqualTo("IMAGE");
    }

    @Test
    @DisplayName("문서는 FILE 로 분류돼 attachment 로 내려간다")
    void documentsAreServedAsDownloads() {
        stored("report.pdf", "application/pdf", "FILE");

        MessageAttachment served = service.messageAttachment(viewer, 1L);

        assertThat(served.getContentType()).isEqualTo("application/pdf");
        assertThat(served.getAttachmentType()).isEqualTo("FILE");
    }

    private void stored(String originalFilename, String storedContentType, String storedType) {
        MessageAttachment a = new MessageAttachment();
        a.setId(1L);
        a.setOriginalFilename(originalFilename);
        a.setStoredFilename("1-1-uuid" + originalFilename);
        a.setContentType(storedContentType);
        a.setAttachmentType(storedType);
        a.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(communicationMapper.findAttachment(1L, 9L)).thenReturn(a);
    }

    private CustomUserDetails viewer() {
        UserAccount u = new UserAccount();
        u.setId(9L);
        u.setUsername("viewer");
        u.setName("viewer");
        u.setActive(true);
        u.setRoles(List.of("EMPLOYEE"));
        return new CustomUserDetails(u);
    }
}
