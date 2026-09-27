package dev.plsql.spring.meta;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Определяет, откуда берутся сигнатуры подпрограмм (имена, типы и направления аргументов).
 *
 * <p>По умолчанию сигнатуры читаются из словаря данных Oracle
 * ({@link DictionarySignatureSource}); тесты и офлайн-инструменты могут подставить
 * заранее заготовленные описания без обращения к базе.
 */
@FunctionalInterface
public interface SignatureSource {

    /**
     * Возвращает все перегрузки процедуры или функции.
     *
     * @param schema      схема-владелец или {@code null}, чтобы имя разрешалось так, как его
     *                    видит подключённый пользователь (тогда работают синонимы)
     * @param packageName пакет или {@code null} для автономной подпрограммы
     * @param name        имя подпрограммы
     * @return перегрузки; пустой список, если такой подпрограммы нет
     */
    List<SubprogramInfo> find(String schema, String packageName, String name);

    /**
     * Возвращает сигнатуры сразу нескольких подпрограмм одного пакета (или нескольких
     * автономных): имя → перегрузки, по одной записи на каждое запрошенное имя.
     *
     * <p>Источник на основе словаря читает их за несколько запросов на всех, а не по
     * несколько запросов на каждое имя. Реализация по умолчанию просто вызывает
     * {@link #find} для каждого имени по очереди.
     *
     * @param schema      схема-владелец или {@code null}, чтобы имя разрешалось так, как его
     *                    видит подключённый пользователь
     * @param packageName пакет или {@code null} для автономных подпрограмм
     * @param names       имена подпрограмм
     * @return {@code Map} «запрошенное имя → перегрузки» в порядке {@code names}; пустой
     *         список перегрузок означает, что подпрограммы нет
     */
    default Map<String, List<SubprogramInfo>> findAll(String schema, String packageName, Collection<String> names) {
        Map<String, List<SubprogramInfo>> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, find(schema, packageName, n));
        }
        return out;
    }
}
