package dev.plsql.spring.call;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Поставляет значения аргументов PL/SQL, которые Java-метод не объявляет.
 *
 * <p>Старые PL/SQL API часто принимают один и тот же контекст почти в каждом вызове:
 * организацию (tenant), текущего пользователя, режим ({@code NTENANT}, {@code NMODE}).
 * Объявлять их в каждом методе — лишний шум, поэтому приложение регистрирует один бин,
 * который знает, откуда брать эти значения.
 *
 * <p>Как это работает. При старте {@link CallPlanner} строит план вызова и для каждого
 * входного аргумента (IN или IN OUT), которому не нашлось параметра Java-метода, вызывает
 * {@link #lookup}. Если вернулся поставщик, он встраивается в план и вызывается при каждом
 * вызове процедуры, поэтому значение всегда текущее (например, берётся из контекста
 * запроса). Значение отсюда важнее значения {@code DEFAULT}, объявленного у аргумента в
 * PL/SQL: аргумент с {@code DEFAULT} пропускается в вызове, только если здесь для него
 * ничего нет.
 *
 * <p>Поставщик может быть вызван и при старте: когда под Java-метод подходят несколько
 * перегрузок процедуры, {@link CallPlanner} пробно вычисляет входные значения, чтобы
 * сравнить типы. Исключение, брошенное поставщиком в этот момент, игнорируется.
 *
 * <p>Пример:
 * <pre>{@code
 * ArgumentDefaults defaults = ArgumentDefaults.byName(Map.of("NTENANT", ctx::tenant));
 * }</pre>
 */
@FunctionalInterface
public interface ArgumentDefaults {

    /**
     * Возвращает поставщика значения для аргумента, которого нет среди параметров Java-метода.
     *
     * <p>Вызывается при старте, пока строится план вызова; сам поставщик затем вызывается при
     * каждом вызове подпрограммы.
     *
     * @param subprogram подпрограмма (процедура или функция, одна перегрузка), для которой
     *                   строится вызов
     * @param argument   аргумент этой подпрограммы, которому не нашлось параметра Java
     * @return поставщик значения или {@code null}, если значения по умолчанию для этого
     *         аргумента нет
     */
    Supplier<Object> lookup(SubprogramInfo subprogram, ArgumentInfo argument);

    /**
     * Возвращает реализацию, которая ничего не поставляет: {@link #lookup} всегда возвращает
     * {@code null}.
     *
     * <p>Это значение по умолчанию в {@code PlsqlApiFactory}, если приложение не задало своё.
     * Тогда аргументы без параметра Java обрабатываются остальными правилами
     * {@link CallPlanner}: {@code DEFAULT} в PL/SQL, {@code @Procedure(nullForMissing = true)}
     * или ошибка при старте.
     *
     * @return пустая реализация
     */
    static ArgumentDefaults none() {
        return (s, a) -> null;
    }

    /**
     * Создаёт реализацию, которая ищет поставщика по имени аргумента без учёта регистра:
     * {@code Map.of("NTENANT", tenant::current)}.
     *
     * <p>Имя сравнивается целиком: префиксы и подчёркивания здесь не отбрасываются, в отличие
     * от сопоставления с именами параметров Java. Подпрограмма не учитывается: одно имя
     * подходит к одноимённому аргументу любой процедуры. Для аргумента без имени (так в
     * словаре Oracle описано возвращаемое значение функции) возвращается {@code null}.
     * Переданная карта копируется при создании, её последующие изменения не видны.
     *
     * @param values поставщики значений по именам аргументов PL/SQL
     * @return реализация, которая выбирает поставщика по имени аргумента
     * @throws IllegalArgumentException если одно имя задано дважды в разном регистре или у имени
     *                                  нет поставщика ({@code null})
     *                                  (например, {@code NTENANT} и {@code ntenant})
     */
    static ArgumentDefaults byName(Map<String, Supplier<Object>> values) {
        Map<String, Supplier<Object>> upper = new java.util.HashMap<>();
        values.forEach((k, v) -> {
            if (v == null) {
                throw new IllegalArgumentException("argument " + k + " has no supplier");
            }
            if (upper.containsKey(k.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("argument " + k + " is given twice");
            }
            upper.put(k.toUpperCase(Locale.ROOT), v);
        });
        return (s, a) -> a.name() == null ? null : upper.get(a.name().toUpperCase(Locale.ROOT));
    }
}
