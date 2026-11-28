package com.portfolio.worksystem.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 월별 로그 아카이브 동작 검증.
 *
 * 회귀 검출 시나리오:
 *  - 활성 로그(application.log)나 다른 달 파일까지 쓸어 담으면 운영 로그가 사라진다.
 *  - 검증 없이 원본을 지우면 깨진 아카이브만 남고 로그가 유실된다.
 *  - 연도 폴더 자동 생성이 빠지면 해가 바뀔 때 잡이 죽는다.
 */
class LogArchiveServiceTest {

    @TempDir
    Path logDir;

    private void writeLog(String name, String body) throws IOException {
        Files.writeString(logDir.resolve(name), body, StandardCharsets.UTF_8);
    }

    private Map<String, Long> readTar(Path archive) throws IOException {
        Map<String, Long> entries = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(new BufferedInputStream(in));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                entries.put(entry.getName(), entry.getSize());
            }
        }
        return entries;
    }

    @Test
    void 한달치를_연도폴더_아래_targz로_묶고_원본을_지운다() throws IOException {
        writeLog("application.2026-06-01.log", "6월 1일");
        writeLog("application.2026-06-02.log", "6월 2일 로그");
        writeLog("application.2026-06-30.log", "6월 30일");

        Path archive = LogArchiveService.archiveMonth(logDir, YearMonth.of(2026, 6));

        assertNotNull(archive, "아카이브가 만들어져야 한다");
        assertEquals(logDir.resolve("2026").resolve("application-2026-06.tar.gz"), archive);
        assertTrue(Files.isDirectory(logDir.resolve("2026")), "연도 폴더가 없으면 생성되어야 한다");

        Map<String, Long> entries = readTar(archive);
        assertEquals(List.of("application.2026-06-01.log",
                        "application.2026-06-02.log",
                        "application.2026-06-30.log"),
                new ArrayList<>(entries.keySet()));
        assertEquals("6월 2일 로그".getBytes(StandardCharsets.UTF_8).length,
                entries.get("application.2026-06-02.log").intValue());

        assertFalse(Files.exists(logDir.resolve("application.2026-06-01.log")), "원본은 삭제되어야 한다");
        assertFalse(Files.exists(logDir.resolve("application.2026-06-30.log")));
        assertEquals(0, Files.list(logDir).filter(Files::isRegularFile).count(),
                "묶인 뒤에는 일별 로그가 남지 않아야 한다");
    }

    @Test
    void 활성로그와_다른달_파일은_건드리지_않는다() throws IOException {
        writeLog("application.log", "당일치 — 아직 쓰는 중");
        writeLog("application.2026-06-30.log", "6월");
        writeLog("application.2026-07-01.log", "7월");
        writeLog("other.log", "관계없는 파일");

        LogArchiveService.archiveMonth(logDir, YearMonth.of(2026, 6));

        assertTrue(Files.exists(logDir.resolve("application.log")), "활성 로그는 보존되어야 한다");
        assertTrue(Files.exists(logDir.resolve("application.2026-07-01.log")), "다른 달은 보존되어야 한다");
        assertTrue(Files.exists(logDir.resolve("other.log")), "무관한 파일은 보존되어야 한다");
        assertFalse(Files.exists(logDir.resolve("application.2026-06-30.log")), "대상 달만 묶여야 한다");
    }

    @Test
    void 대상이_없으면_아무것도_만들지_않는다() throws IOException {
        writeLog("application.log", "당일치");

        assertNull(LogArchiveService.archiveMonth(logDir, YearMonth.of(2026, 6)));
        assertFalse(Files.exists(logDir.resolve("2026")), "빈 연도 폴더를 만들면 안 된다");
    }

    @Test
    void 같은_아카이브가_이미_있으면_덮어쓰지_않고_원본을_보존한다() throws IOException {
        Path yearDir = Files.createDirectories(logDir.resolve("2026"));
        Files.writeString(yearDir.resolve("application-2026-06.tar.gz"), "기존 아카이브");
        writeLog("application.2026-06-15.log", "6월 15일");

        assertNull(LogArchiveService.archiveMonth(logDir, YearMonth.of(2026, 6)));

        assertEquals("기존 아카이브",
                Files.readString(yearDir.resolve("application-2026-06.tar.gz")),
                "기존 아카이브를 덮어쓰면 안 된다");
        assertTrue(Files.exists(logDir.resolve("application.2026-06-15.log")),
                "건너뛴 경우 원본을 지우면 안 된다");
    }

    @Test
    void 임시파일을_남기지_않는다() throws IOException {
        writeLog("application.2026-06-01.log", "6월");

        LogArchiveService.archiveMonth(logDir, YearMonth.of(2026, 6));

        try (var files = Files.list(logDir.resolve("2026"))) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count(),
                    "임시파일이 남으면 안 된다");
        }
    }

    @Test
    void 미아카이브_월을_과거만_오름차순으로_찾는다() throws IOException {
        writeLog("application.2026-05-31.log", "5월");
        writeLog("application.2026-07-02.log", "7월");
        writeLog("application.2026-06-10.log", "6월");
        writeLog("application.2026-08-01.log", "이번 달 — 제외 대상");
        writeLog("application.log", "당일치");

        List<YearMonth> pending = LogArchiveService.pendingMonths(logDir, YearMonth.of(2026, 8));

        assertEquals(List.of(YearMonth.of(2026, 5), YearMonth.of(2026, 6), YearMonth.of(2026, 7)), pending,
                "이번 달은 아직 쌓이는 중이라 제외하고, 과거 달만 오름차순으로 나와야 한다");
    }

    @Test
    void 해가_바뀌어도_각_연도폴더로_들어간다() throws IOException {
        writeLog("application.2025-12-31.log", "작년 마지막날");
        writeLog("application.2026-01-01.log", "새해 첫날");

        Path y2025 = LogArchiveService.archiveMonth(logDir, YearMonth.of(2025, 12));
        Path y2026 = LogArchiveService.archiveMonth(logDir, YearMonth.of(2026, 1));

        assertEquals(logDir.resolve("2025").resolve("application-2025-12.tar.gz"), y2025);
        assertEquals(logDir.resolve("2026").resolve("application-2026-01.tar.gz"), y2026);
        assertTrue(Files.isDirectory(logDir.resolve("2025")));
        assertTrue(Files.isDirectory(logDir.resolve("2026")));
    }
}
