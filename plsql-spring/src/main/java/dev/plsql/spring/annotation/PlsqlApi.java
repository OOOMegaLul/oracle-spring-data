package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает интерфейс, методы которого — подпрограммы PL/SQL (процедуры и функции).
 *
 * <pre>
 * &#64;PlsqlApi(packageName = "APP_CONTEXT")
 * interface SessionApi {
 *     void setParam(String name, String value);   // APP_CONTEXT.SET_PARAM(SNAME, SVALUE)
 * }
 *
 * &#64;PlsqlApi                                   // автономные процедуры и функции
 * interface CatalogApi {
 *     &#64;Procedure("P_FOLDER_DELETE")
 *     void deleteFolder(long tenant, long rn);
 * }
 * </pre>
 *
 * <p>Реализацию интерфейса библиотека создаёт сама при старте приложения, так же как Spring Data
 * создаёт репозитории. Каждый метод сверяется со словарём Oracle ALL_ARGUMENTS (системным
 * представлением со списком аргументов всех доступных подпрограмм) ещё до окончания старта
 * контекста, поэтому переименованный в базе параметр останавливает старт, а не первый вызов.
 *
 * <p>Методы с {@link SqlQuery} выполняют SQL-запрос, а не вызывают подпрограмму; имя
 * подпрограммы можно задать явно через {@link Procedure}, имя аргумента — через {@link Arg}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlsqlApi {

    /**
     * Возвращает имя пакета PL/SQL, в котором лежат подпрограммы, например {@code APP_CONTEXT}.
     *
     * <p>Пустая строка означает, что методы соответствуют автономным (не входящим в пакет)
     * процедурам и функциям.
     *
     * @return имя пакета или пустая строка
     */
    String packageName() default "";

    /**
     * Возвращает схему-владельца (в Oracle схема — это пользователь, которому принадлежат
     * объекты), например {@code APP}.
     *
     * <p>Пустая строка означает схему пользователя, под которым открыто соединение; имена
     * при этом разрешаются с учётом синонимов (альтернативных имён объектов), так же как их
     * разрешает компилятор PL/SQL.
     *
     * @return имя схемы или пустая строка
     */
    String schema() default "";
}
