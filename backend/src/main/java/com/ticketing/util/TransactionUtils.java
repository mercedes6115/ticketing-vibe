package com.ticketing.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class TransactionUtils {

    private TransactionUtils() {}

    /**
     * 현재 트랜잭션 커밋 이후에 action을 실행한다.
     * 커밋 전 실행 시 롤백되더라도 action이 이미 수행되는 사이드 이펙트를 방지한다.
     */
    public static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
