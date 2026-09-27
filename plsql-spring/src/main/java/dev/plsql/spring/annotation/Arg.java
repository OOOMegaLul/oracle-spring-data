package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Явно задаёт имя аргумента PL/SQL, которому соответствует параметр метода или компонент record.
 *
 * <p>Без этой аннотации имя параметра Java сопоставляется с именем аргумента без учёта
 * регистра, подчёркиваний и однобуквенного префикса типа ({@code tenant} подходит к
 * {@code NTENANT}, {@code P_TENANT} и {@code TENANT}). Если параметру одинаково хорошо
 * подходят два аргумента, библиотека не угадывает, а останавливает старт приложения
 * и просит указать имя через {@code @Arg}.
 *
 * <p>Имя из аннотации сравнивается с именем аргумента целиком, только без учёта регистра:
 * префиксы и подчёркивания здесь уже не отбрасываются.
 *
 * <p>Где действует:
 * <ul>
 *   <li>на параметре метода интерфейса {@link PlsqlApi} — имя аргумента процедуры или функции;</li>
 *   <li>на параметре метода с {@link SqlQuery} — имя именованного параметра в тексте запроса
 *       ({@code :rn});</li>
 *   <li>на компоненте record (record — неизменяемый класс-«запись» Java 16+) — имя аргумента
 *       или поля PL/SQL-записи, которое этот компонент передаёт или принимает: когда record
 *       служит объектом-параметром, собирает несколько OUT-аргументов или заполняется из
 *       записи {@code RECORD} либо объектного типа; у строк курсора и {@code @SqlQuery} — имя
 *       колонки;</li>
 *   <li>на поле, getter'е или setter'е бина (обычного класса со свойствами) — то же, что на
 *       компоненте record.</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface Arg {

    /**
     * Возвращает имя аргумента PL/SQL (или именованного параметра SQL), например {@code NRN}.
     *
     * @return имя аргумента; регистр букв не важен
     */
    String value();
}
