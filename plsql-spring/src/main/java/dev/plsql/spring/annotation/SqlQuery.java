package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает метод, который выполняет обычный SQL-запрос (как правило, SELECT) в том же
 * интерфейсе {@link PlsqlApi}, вместо вызова процедуры; аналог {@code @Query} в Spring Data.
 *
 * <p>Параметры передаются по имени ({@code :rn}): имя берётся из имени параметра Java или из
 * {@link Arg}. Запрос разбирается один раз при старте, и если он ссылается на параметр,
 * которого у метода нет, старт приложения останавливается с ошибкой. Строки превращаются в
 * record или обычные классы по именам колонок: {@code BEGIN_DATE} попадает в
 * {@code beginDate}; одна колонка — в простой тип, прочее — в {@code Map}.
 *
 * <p>Типы результата: {@code List<T>}, {@code Optional<T>}, {@code T} или скалярное значение.
 * С параметром {@code Pageable} или {@code Sort} из Spring Data запрос выполняется постранично
 * или с сортировкой (через {@code ROWNUM}: Oracle 11g не знает {@code OFFSET ... FETCH}), а
 * результат может быть ещё {@code Page<T>} или {@code Slice<T>}.
 * Если метод ждёт одну строку, а запрос вернул несколько, при вызове бросается
 * {@code IncorrectResultSizeDataAccessException}. Возвращает ли оператор строки или число
 * изменённых строк (для UPDATE и т.п.), решает JDBC при выполнении, а не догадка по первому
 * слову запроса; число изменённых строк приводится к типу результата метода.
 *
 * <p>Ошибки разбираются так же, как у процедур: {@code RAISE_APPLICATION_ERROR} (способ, которым
 * PL/SQL-код, например триггер, сообщает о нарушении бизнес-правила с кодом ORA-20000..20999)
 * становится {@code PlsqlBusinessException}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SqlQuery {

    /**
     * Возвращает текст SQL-запроса с именованными параметрами вида {@code :name}.
     *
     * @return текст запроса
     */
    String value();

    /**
     * Возвращает, сколько секунд запрос может выполняться, прежде чем драйвер его прервёт.
     *
     * <p>Работает так же, как {@link Procedure#timeout()}: {@code -1} (по умолчанию) — как у
     * фабрики, {@code 0} — без ограничения, внутри {@code @Transactional(timeout = ...)} действует
     * меньшее из двух сроков.
     *
     * @return предельное время запроса в секундах, {@code 0} или {@code -1}
     */
    int timeout() default -1;
}
