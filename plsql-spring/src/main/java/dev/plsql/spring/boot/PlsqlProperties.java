package dev.plsql.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.plsql.spring.support.CharsetGuard;

/**
 * {@code plsql.*} settings.
 */
@ConfigurationProperties("plsql")
public class PlsqlProperties {

    /** What to do with text the database character set cannot store. */
    private CharsetGuard.Policy charsetPolicy = CharsetGuard.Policy.FAIL;

    /** NLS_CHARACTERSET; read from the database when empty. */
    private String databaseCharset;

    /** Capacity reserved for OUT index-by tables. */
    private int indexTableMaxLength = 10_000;

    /** Repeat a call once after ORA-04068 (package recompiled under a live session). */
    private boolean retryDiscardedState = true;

    public CharsetGuard.Policy getCharsetPolicy() {
        return charsetPolicy;
    }

    public void setCharsetPolicy(CharsetGuard.Policy charsetPolicy) {
        this.charsetPolicy = charsetPolicy;
    }

    public String getDatabaseCharset() {
        return databaseCharset;
    }

    public void setDatabaseCharset(String databaseCharset) {
        this.databaseCharset = databaseCharset;
    }

    public int getIndexTableMaxLength() {
        return indexTableMaxLength;
    }

    public void setIndexTableMaxLength(int indexTableMaxLength) {
        this.indexTableMaxLength = indexTableMaxLength;
    }

    public boolean isRetryDiscardedState() {
        return retryDiscardedState;
    }

    public void setRetryDiscardedState(boolean retryDiscardedState) {
        this.retryDiscardedState = retryDiscardedState;
    }
}
