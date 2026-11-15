package com.portfolio.worksystem.util;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 <b>커밋된 뒤에</b> 실행해야 하는 작업.
 *
 * <p>주 용도는 <b>되돌릴 수 없는 부수효과</b>다 — 파일 삭제, 외부 알림 발송처럼 DB 롤백이
 * 원상복구해 주지 못하는 것들.
 *
 * <p>왜 필요한가 — 트랜잭션 안에서 파일을 먼저 지우면, 뒤이은 DB 작업이 실패해 롤백될 때
 * <b>파일만 사라지고 레코드는 남는다.</b> 사용자에게는 "삭제 실패" 라고 안내되지만 첨부는
 * 이미 없어져, 목록에 보이는데 열면 깨지는 상태가 된다. 커밋이 확정된 뒤에 지우면
 * 이 어긋남이 생기지 않는다.
 *
 * <p>반대 방향(업로드)은 순서를 바꿔서는 못 고친다. 파일을 먼저 써야 경로를 DB 에 넣을 수
 * 있기 때문이다. 대신 롤백됐을 때 되치운다 — {@link #deleteUploadOnRollback}.
 *
 * <p>트랜잭션 밖에서 불리면 <b>즉시 실행</b>한다. 그래야 호출부가 자기가 트랜잭션 안인지
 * 신경 쓰지 않아도 된다.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    /** 커밋 후 실행. 트랜잭션이 없으면 지금 실행한다. */
    public static void run(Runnable task) {
        if (task == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /**
     * 방금 저장한 업로드 파일을 <b>롤백되면</b> 지운다.
     *
     * <p>업로드는 경로를 DB 에 넣으려면 파일을 먼저 써야 해서 순서를 바꿀 수 없다. 그래서
     * 뒤이은 DB 작업이 실패하면 아무도 참조하지 않는 파일이 디스크에 남는다. 깨진 링크보다는
     * 덜 해롭지만 지워 줄 사람이 없어 계속 쌓인다.
     *
     * <p>{@code what} 은 실패를 로그로 남길 때 쓰는 사람이 읽을 이름이다("첨부파일" 등).
     * 정리에 실패해도 예외를 올리지 않는다 — 이미 롤백된 트랜잭션을 뒤늦게 더 망가뜨릴 뿐이다.
     *
     * <p>트랜잭션 밖이면 아무것도 하지 않는다. 롤백될 일이 없으니 되치울 것도 없다.
     */
    public static void deleteUploadOnRollback(Path file, String what) {
        if (file == null) {
            return;
        }
        onRollback(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                log.warn("롤백된 {} 파일 정리 실패: {} ({})", what, file, e.getMessage());
            }
        });
    }

    /** 롤백된 경우에만 실행. 업로드 파일 정리는 {@link #deleteUploadOnRollback} 을 쓴다. */
    public static void onRollback(Runnable task) {
        if (task == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    task.run();
                }
            }
        });
    }
}
