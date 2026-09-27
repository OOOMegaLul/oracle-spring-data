package com.example.hr;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Пакет {@code HR_API} как обычный интерфейс Java. Реализацию пишет библиотека: при старте
 * приложения она находит каждую процедуру в словаре Oracle и сверяет её с методом.
 */
@PlsqlApi(packageName = "HR_API")
public interface HrApi {

    /**
     * Сотрудник — одна строка курсора {@code LIST_ACTIVE}. Колонки ложатся на компоненты по
     * именам: {@code FULL_NAME} → {@code fullName}.
     *
     * @param id       номер
     * @param fullName имя
     * @param hired    дата приёма
     */
    record Employee(long id, String fullName, LocalDate hired) {
    }

    /**
     * {@code HR_API.HIRE(P_NAME, P_HIRED DEFAULT SYSDATE, P_ID OUT)}.
     *
     * <p>Параметр {@code name} находит аргумент {@code P_NAME} (префикс {@code P_} не нужен).
     * {@code P_HIRED} не передаём: у него в PL/SQL есть {@code DEFAULT}. Единственный
     * OUT-аргумент {@code P_ID} становится результатом метода.
     *
     * @param name имя сотрудника
     * @return номер нового сотрудника
     */
    long hire(String name);

    /**
     * {@code HR_API.FIND_NAME(P_ID) RETURN VARCHAR2}; функция может вернуть {@code NULL}, поэтому
     * результат — {@code Optional}.
     *
     * @param id номер сотрудника
     * @return имя или пусто, если такого сотрудника нет
     */
    Optional<String> findName(long id);

    /**
     * {@code HR_API.IS_ACTIVE(P_ID) RETURN BOOLEAN}.
     *
     * @param id номер сотрудника
     * @return работает ли сотрудник
     */
    boolean isActive(long id);

    /**
     * {@code HR_API.LIST_ACTIVE(P_CUR OUT SYS_REFCURSOR)}: курсор превращается в список.
     *
     * @return работающие сотрудники
     */
    List<Employee> listActive();

    /**
     * {@code HR_API.FIRE(P_ID)}.
     *
     * @param id номер сотрудника
     */
    void fire(long id);
}
