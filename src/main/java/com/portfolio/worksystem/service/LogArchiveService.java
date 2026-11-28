package com.portfolio.worksystem.service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * [LOG-1] 월별 로그 아카이브 — 일별 롤링 로그를 달 단위 tar.gz로 묶어 연도 폴더에 보관한다.
 *
 * <pre>
 *   /var/log/worksystem/application.log                  당일치(Logback이 쓰는 중)
 *   /var/log/worksystem/application.2026-07-28.log       일별 롤링(logback-spring.xml)
 *   /var/log/worksystem/2026/application-2026-06.tar.gz  이 서비스가 만드는 월별 아카이브
 * </pre>
 *
 * 설계 메모:
 *  - Logback/Log4j2 모두 롤링 압축은 개별 파일 단위(.gz/.zip)만 지원한다. "한 달치를 하나로"는
 *    프레임워크 기능 밖이라 스케줄러가 맡는다.
 *  - 원본 삭제 전에 tar를 다시 읽어 파일 수·크기를 대조한다. 검증에 실패하면 원본을 남긴다.
 *  - 임시파일에 쓴 뒤 옮기므로 쓰는 도중 프로세스가 죽어도 반쪽 아카이브가 남지 않는다.
 *  - 같은 이름의 아카이브가 이미 있으면 덮어쓰지 않고 건너뛴다(원본도 보존). 유실보다 중복이 낫다.
 */
@Slf4j
@Service
public class LogArchiveService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** logback-spring.xml의 fileNamePattern(application.%d&#123;yyyy-MM-dd&#125;.log)과 짝을 이룬다. */
    private static final Pattern ROLLED_LOG = Pattern.compile("^application\\.(\\d{4})-(\\d{2})-\\d{2}\\.log$");

    @Value("${app.log.dir:logs}")
    private String logDir;

    /** 매월 1일 03:30 — 전월 일별 로그를 묶는다. */
    @Scheduled(cron = "0 30 3 1 * *", zone = "Asia/Seoul")
    public void archivePreviousMonth() {
        archiveQuietly(YearMonth.now(ZONE).minusMonths(1));
    }

    /**
     * 기동 시 보정 — 앱이 월초에 꺼져 있었으면 위 스케줄이 통째로 걸러진다.
     * 남아있는 일별 로그 중 이번 달보다 과거인 달을 전부 훑는다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void archiveMissedMonths() {
        Path dir = Path.of(logDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<YearMonth> pending;
        try {
            pending = pendingMonths(dir, YearMonth.now(ZONE));
        } catch (IOException e) {
            log.error("[로그아카이브] 미아카이브 월 조회 실패 — 기동은 계속한다", e);
            return;
        }
        for (YearMonth ym : pending) {
            archiveQuietly(ym);
        }
    }

    /** 아카이브 실패가 기동이나 운영을 막아서는 안 된다. 실패 시 원본 일별 로그는 그대로 남는다. */
    private void archiveQuietly(YearMonth ym) {
        try {
            Path archive = archiveMonth(Path.of(logDir), ym);
            if (archive != null) {
                log.info("[로그아카이브] {} 생성 완료: {}", ym, archive);
            }
        } catch (IOException e) {
            log.error("[로그아카이브] {} 처리 실패 — 원본 일별 로그는 보존됨", ym, e);
        }
    }

    /** 아직 묶이지 않은 과거 달(오름차순). 이번 달은 아직 쌓이는 중이라 제외한다. */
    static List<YearMonth> pendingMonths(Path dir, YearMonth current) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> ROLLED_LOG.matcher(p.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))))
                    .filter(ym -> ym.isBefore(current))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    /**
     * 해당 달의 일별 로그를 {@code {dir}/{yyyy}/application-{yyyy-MM}.tar.gz}로 묶고 원본을 지운다.
     * 연도 폴더가 없으면 만든다.
     *
     * @return 생성된 아카이브 경로. 대상 파일이 없거나 이미 아카이브가 있어 건너뛰면 null.
     */
    static Path archiveMonth(Path dir, YearMonth ym) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<Path> targets = monthlyLogs(dir, ym);
        if (targets.isEmpty()) {
            return null;
        }
        Path yearDir = dir.resolve(String.valueOf(ym.getYear()));
        Files.createDirectories(yearDir);

        Path archive = yearDir.resolve("application-" + ym + ".tar.gz");
        if (Files.exists(archive)) {
            log.warn("[로그아카이브] {} 아카이브가 이미 존재해 건너뜀 — 일별 로그 {}개 보존: {}",
                    ym, targets.size(), archive);
            return null;
        }

        Path temp = Files.createTempFile(yearDir, "application-" + ym + "-", ".tar.gz.tmp");
        try {
            writeTarGz(temp, targets);
            verifyContents(temp, targets);
            Files.move(temp, archive, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        for (Path original : targets) {
            Files.delete(original);
        }
        return archive;
    }

    /** 해당 달에 속한 롤링 로그(파일명 오름차순). 활성 파일 application.log는 패턴에 걸리지 않아 자연히 제외된다. */
    private static List<Path> monthlyLogs(Path dir, YearMonth ym) throws IOException {
        String prefix = "application." + ym + "-";
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && ROLLED_LOG.matcher(name).matches();
                    })
                    .sorted()
                    .toList();
        }
    }

    private static void writeTarGz(Path archive, List<Path> files) throws IOException {
        try (OutputStream out = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(new BufferedOutputStream(out));
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Path file : files) {
                String name = file.getFileName().toString();
                TarArchiveEntry entry = new TarArchiveEntry(file, name);
                tar.putArchiveEntry(entry);
                Files.copy(file, tar);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    /** 원본을 지우기 전 마지막 관문 — tar를 되읽어 항목명과 크기가 원본과 일치하는지 본다. */
    private static void verifyContents(Path archive, List<Path> expected) throws IOException {
        Map<String, Long> want = new LinkedHashMap<>();
        for (Path file : expected) {
            want.put(file.getFileName().toString(), Files.size(file));
        }
        Map<String, Long> found = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(new BufferedInputStream(in));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                found.put(entry.getName(), entry.getSize());
            }
        }
        if (!want.equals(found)) {
            throw new IOException("아카이브 검증 실패 — 기대: " + want + ", 실제: " + found);
        }
    }
}
