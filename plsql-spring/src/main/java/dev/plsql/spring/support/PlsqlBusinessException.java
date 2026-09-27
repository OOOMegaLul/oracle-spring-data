package dev.plsql.spring.support;

import org.springframework.dao.NonTransientDataAccessException;

/**
 * RAISE_APPLICATION_ERROR from PL/SQL: a business rule said no.
 * {@link #getMessage()} is the text the PL/SQL code wrote, without the
 * {@code ORA-20001:} prefix and the ORA-06512 stack underneath it,
 * i.e. what a user should see.
 */
public class PlsqlBusinessException extends NonTransientDataAccessException {

    private final int errorCode;

    public PlsqlBusinessException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 20000..20999 */
    public int getErrorCode() {
        return errorCode;
    }
}
