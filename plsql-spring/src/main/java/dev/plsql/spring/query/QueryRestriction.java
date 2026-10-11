package dev.plsql.spring.query;

import java.util.Objects;
import java.util.function.Function;

/**
 * Обязательное ограничение строк: условие, которое библиотека сама добавляет к каждому запросу
 * {@code @SqlQuery}, помеченному {@link dev.plsql.spring.annotation.Restricted} с именем этого
 * ограничения. Например, «организация записи — среди доступных пользователю»: разработчик
 * реестра не пишет его в каждом запросе и не может забыть.
 *
 * <pre>{@code
 * @Bean
 * QueryRestriction orgs(CurrentUser user) {   // имя бина — имя ограничения
 *     return QueryRestriction.on("ORG_ID", field -> Filter.inSelect(field,
 *             "select org_id from user_orgs where user_name = ?", user.name()));
 * }
 *
 * @Restricted("orgs")
 * @PlsqlApi
 * interface OrderRegistry { ... }
 * }</pre>
 *
 * <p>Условие строится заново при каждом вызове: оно может зависеть от текущего пользователя.
 * Ограничение проверяется по колонкам запроса так же, как {@link Filter}, но без белого списка:
 * это код приложения, а не ввод пользователя. Вернуть {@code null} нельзя — это ошибка вызова, а
 * не «без ограничения»; снять ограничение для запроса может только явный {@link Filter#none()}.
 */
@FunctionalInterface
public interface QueryRestriction {

    /**
     * Строит условие для поля запроса, по которому ограничиваются строки.
     *
     * @param field имя поля: из {@code @Restricted(field = ...)} или {@link #defaultField()}
     * @return условие; {@link Filter#none()} — без ограничения, но не {@code null}
     */
    Filter apply(String field);

    /**
     * Возвращает поле, по которому ограничиваются строки, если в
     * {@link dev.plsql.spring.annotation.Restricted} оно не указано.
     *
     * @return имя поля или {@code null}: тогда его нужно указать в каждом {@code @Restricted}
     */
    default String defaultField() {
        return null;
    }

    /**
     * Создаёт ограничение с полем по умолчанию.
     *
     * @param defaultField поле запроса по умолчанию, например {@code ORG_ID}
     * @param condition    условие для поля
     * @return ограничение
     */
    static QueryRestriction on(String defaultField, Function<String, Filter> condition) {
        Objects.requireNonNull(condition, "condition");
        return new QueryRestriction() {
            /**
             * Строит условие для поля.
             *
             * @param field поле запроса
             * @return условие
             */
            @Override
            public Filter apply(String field) {
                return condition.apply(field);
            }

            /**
             * Возвращает поле по умолчанию.
             *
             * @return поле
             */
            @Override
            public String defaultField() {
                return defaultField;
            }
        };
    }
}
