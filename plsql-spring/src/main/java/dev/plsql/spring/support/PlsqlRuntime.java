package dev.plsql.spring.support;

import javax.sql.DataSource;

import dev.plsql.spring.call.CallExecutor;
import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.SignatureSource;

/**
 * Everything a generated implementation needs at runtime. Built by
 * {@link dev.plsql.spring.PlsqlApiFactory}.
 *
 * @param retryDiscardedState repeat a call once after ORA-04068 (package recompiled under a
 *                            live session); the failed call never ran, so this is safe
 */
public record PlsqlRuntime(
        DataSource dataSource,
        SignatureSource signatures,
        CallPlanner planner,
        CallExecutor executor,
        PlsqlExceptionTranslator translator,
        CharsetGuard charsetGuard,
        boolean retryDiscardedState) {
}
