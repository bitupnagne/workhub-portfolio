package com.portfolio.worksystem.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 되돌릴 수 없는 부수효과(파일 삭제 등)를 커밋 뒤로 미루는 장치.
 *
 * <p>왜 중요한가 — 트랜잭션 안에서 파일을 먼저 지우면 뒤이은 DB 작업이 실패해 롤백될 때
 * <b>파일만 사라지고 레코드는 남는다.</b> 목록에는 보이는데 열면 깨지고, 사용자에게는
 * "삭제 실패" 라고 안내되므로 더 헷갈린다.
 *
 * <p>실제 트랜잭션 매니저 대신 {@link TransactionSynchronizationManager} 를 직접 열고 닫아
 * 커밋/롤백 시점을 재현한다 — DB 없이 이 장치의 계약만 검증하기 위해서다.
 */
class AfterCommitTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션이 없으면 즉시 실행한다")
    void runsImmediatelyOutsideATransaction() {
        List<String> done = new ArrayList<>();

        AfterCommit.run(() -> done.add("deleted"));

        assertThat(done).containsExactly("deleted");
    }

    @Test
    @DisplayName("트랜잭션 안에서는 커밋 전까지 실행하지 않는다")
    void defersUntilCommit() {
        List<String> done = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> done.add("deleted"));

        assertThat(done).as("아직 커밋 전이므로 파일을 지우면 안 된다").isEmpty();

        commit();
        assertThat(done).containsExactly("deleted");
    }

    /*
      핵심 회귀 — 롤백되면 파일이 남아 있어야 한다. 이게 깨지면 "삭제 실패" 안내를 받고도
      첨부가 사라진, 되돌릴 수 없는 상태가 된다.
    */
    @Test
    @DisplayName("롤백되면 실행하지 않는다 — 파일이 살아남아야 한다")
    void doesNotRunWhenTheTransactionRollsBack() {
        List<String> done = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> done.add("deleted"));
        rollback();

        assertThat(done).isEmpty();
    }

    @Test
    @DisplayName("onRollback 은 롤백에서만 돈다 — 업로드해 둔 고아 파일 정리용")
    void onRollbackRunsOnlyWhenRolledBack() {
        List<String> cleaned = new ArrayList<>();

        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.onRollback(() -> cleaned.add("orphan-removed"));
        commit();
        assertThat(cleaned).as("커밋됐으면 업로드 파일을 지우면 안 된다").isEmpty();

        TransactionSynchronizationManager.initSynchronization();
        AfterCommit.onRollback(() -> cleaned.add("orphan-removed"));
        rollback();
        assertThat(cleaned).containsExactly("orphan-removed");
    }

    @Test
    @DisplayName("등록한 순서대로 실행한다")
    void keepsRegistrationOrder() {
        List<String> done = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.run(() -> done.add("first"));
        AfterCommit.run(() -> done.add("second"));
        commit();

        assertThat(done).containsExactly("first", "second");
    }

    @Test
    @DisplayName("null 작업은 무시한다")
    void nullTaskIsIgnored() {
        AfterCommit.run(null);
        AfterCommit.onRollback(null);
        AfterCommit.deleteUploadOnRollback(null, "없음");
    }

    /* ── 업로드 고아 정리 ─────────────────────────────── */

    @Test
    @DisplayName("롤백되면 업로드해 둔 파일을 지운다")
    void deletesTheUploadWhenTheTransactionRollsBack(@TempDir Path dir) throws IOException {
        Path upload = Files.writeString(dir.resolve("orphan.bin"), "x");
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.deleteUploadOnRollback(upload, "첨부");
        assertThat(upload).as("아직 롤백 전이므로 남아 있어야 한다").exists();

        rollback();
        assertThat(upload).doesNotExist();
    }

    @Test
    @DisplayName("커밋되면 업로드 파일을 지우지 않는다")
    void keepsTheUploadWhenTheTransactionCommits(@TempDir Path dir) throws IOException {
        Path upload = Files.writeString(dir.resolve("kept.bin"), "x");
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.deleteUploadOnRollback(upload, "첨부");
        commit();

        assertThat(upload).exists();
    }

    /*
      트랜잭션 밖에서는 롤백될 일이 없으니 되치울 것도 없다. 여기서 지워 버리면
      방금 저장한 파일이 사라진다.
    */
    @Test
    @DisplayName("트랜잭션 밖이면 아무것도 하지 않는다")
    void doesNothingOutsideATransaction(@TempDir Path dir) throws IOException {
        Path upload = Files.writeString(dir.resolve("standalone.bin"), "x");

        AfterCommit.deleteUploadOnRollback(upload, "첨부");

        assertThat(upload).exists();
    }

    /*
      정리 실패가 예외로 올라오면 이미 롤백된 트랜잭션의 뒷정리를 더 망가뜨린다.
      파일이 이미 없어도(다른 경로에서 먼저 지웠거나) 조용히 넘어가야 한다.
    */
    @Test
    @DisplayName("이미 없는 파일이어도 예외를 올리지 않는다")
    void missingFileIsNotAnError(@TempDir Path dir) {
        TransactionSynchronizationManager.initSynchronization();

        AfterCommit.deleteUploadOnRollback(dir.resolve("gone.bin"), "첨부");

        rollback();
    }

    private void commit() {
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(s -> s.beforeCommit(false));
        syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        syncs.forEach(s -> s.afterCompletion(
                org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void rollback() {
        var syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(s -> s.afterCompletion(
                org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
    }
}
