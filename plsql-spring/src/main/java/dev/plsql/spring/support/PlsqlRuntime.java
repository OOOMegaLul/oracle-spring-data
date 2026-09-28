package dev.plsql.spring.support;

import javax.sql.DataSource;

import dev.plsql.spring.call.CallExecutor;
import dev.plsql.spring.call.CallPlanner;
import dev.plsql.spring.meta.SignatureSource;

/**
 * Хранит всё, что нужно созданной реализации интерфейса {@code @PlsqlApi} во время работы.
 *
 * <p>Собирается в {@link dev.plsql.spring.PlsqlApiFactory} (в её {@code Builder}) один раз на
 * фабрику и передаётся каждой реализации, которую фабрика создаёт. Это record — неизменяемый
 * класс-«запись» Java: поля задаются в конструкторе, а читаются одноимёнными методами
 * ({@code dataSource()}, {@code planner()} и т.д.).
 *
 * @param dataSource          пул соединений, из которого берётся соединение на каждый вызов (в
 *                            транзакции Spring — соединение этой транзакции)
 * @param signatures          источник сигнатур подпрограмм: по умолчанию словарь Oracle, в
 *                            тестах можно подставить заготовки
 * @param planner             планировщик: при старте сопоставляет метод Java с подпрограммой и
 *                            собирает анонимный PL/SQL-блок для вызова
 * @param executor            исполнитель: при каждом вызове передаёт значения в собранный блок,
 *                            выполняет его и читает результат
 * @param translator          переводит ошибки JDBC в исключения Spring и
 *                            {@link PlsqlBusinessException}
 * @param charsetGuard        проверяет, что текст можно сохранить в кодировке базы, до отправки
 *                            (отсюда его берут запросы {@code @SqlQuery}; исполнителю вызовов
 *                            процедур тот же объект передан при создании)
 * @param retryDiscardedState повторять вызов один раз после ORA-04068 (пакет перекомпилировали
 *                            под живой сессией); изменения данных неудачного вызова Oracle уже
 *                            откатил, поэтому для данных это безопасно
 * @param queryTimeout        сколько секунд может длиться вызов или запрос, если на методе не
 *                            задан свой срок; {@code 0} — без ограничения
 * @param fetchSize           сколько строк запроса {@code @SqlQuery} забирать за одно обращение к
 *                            базе; {@code 0} — как у драйвера (10). Курсорам процедур тот же размер
 *                            передан исполнителю при создании
 */
public record PlsqlRuntime(
        DataSource dataSource,
        SignatureSource signatures,
        CallPlanner planner,
        CallExecutor executor,
        PlsqlExceptionTranslator translator,
        CharsetGuard charsetGuard,
        boolean retryDiscardedState,
        int queryTimeout,
        int fetchSize) {
}
