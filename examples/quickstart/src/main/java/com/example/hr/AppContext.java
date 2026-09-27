package com.example.hr;

import java.util.Optional;

import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Пакет {@code APP_CONTEXT}: «кто работает», записанный в переменную пакета.
 */
@PlsqlApi(packageName = "APP_CONTEXT")
public interface AppContext {

    /**
     * {@code APP_CONTEXT.CURRENT_USER_NAME RETURN VARCHAR2}.
     *
     * @return пользователь, которого записал {@code SessionConfig}, или пусто без профиля
     *         {@code session}
     */
    Optional<String> currentUserName();
}
