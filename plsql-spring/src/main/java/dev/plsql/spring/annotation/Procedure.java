package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Явно задаёт имя подпрограммы PL/SQL (процедуры или функции), которую вызывает метод, и
 * настройки этого вызова.
 *
 * <p>Без аннотации имя выводится из имени метода: {@code setParam} превращается в
 * {@code SET_PARAM}. Подпрограмма ищется в пакете и схеме, заданных в {@link PlsqlApi} на
 * интерфейсе.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Procedure {

    /**
     * Возвращает имя подпрограммы, например {@code P_FOLDER_DELETE}.
     *
     * <p>Указывается только имя, без пакета и схемы; регистр не важен, имя приводится к
     * верхнему регистру. Пустая строка (значение по умолчанию) означает, что имя выводится из
     * имени метода.
     *
     * @return имя подпрограммы или пустая строка
     */
    String value() default "";

    /**
     * Указывает, передавать ли NULL в обязательные IN-аргументы, для которых метод не дал значения.
     *
     * <p>Многие старые процедуры объявляют длинные списки аргументов без DEFAULT, и вызывающий
     * код передаёт NULL в большую часть из них; этот флаг говорит об этом один раз, а не для
     * каждого аргумента. Касается только аргументов, которые не пришли ни из параметров метода,
     * ни из бина {@code ArgumentDefaults} и у которых нет DEFAULT в PL/SQL (аргументы с DEFAULT
     * просто не передаются, и срабатывает значение по умолчанию из PL/SQL).
     *
     * <p>По умолчанию выключено: забытый аргумент должен останавливать старт приложения, а не
     * молча превращаться в NULL.
     *
     * @return {@code true}, если недостающие обязательные аргументы заполняются NULL
     */
    boolean nullForMissing() default false;
}
