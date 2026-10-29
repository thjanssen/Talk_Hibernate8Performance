package com.thorben.janssen.talk;

import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionEventListener;

/**
 * Counts the executed JDBC statements and batches of all sessions - registered for every session via
 * hibernate.session.events.auto. Unlike Statistics#getPrepareStatementCount, it also counts statements
 * Hibernate executes again with a reused PreparedStatement.
 */
public class ExecutedStatements implements SessionEventListener {

    private static final AtomicInteger statements = new AtomicInteger();
    private static final AtomicInteger batches = new AtomicInteger();

    public static void reset() {
        statements.set(0);
        batches.set(0);
    }

    public static int statements() {
        return statements.get();
    }

    public static int batches() {
        return batches.get();
    }

    @Override
    public void jdbcExecuteStatementEnd() {
        statements.incrementAndGet();
    }

    @Override
    public void jdbcExecuteBatchEnd() {
        batches.incrementAndGet();
    }
}
