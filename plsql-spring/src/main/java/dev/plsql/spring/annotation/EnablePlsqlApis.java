package dev.plsql.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

import dev.plsql.spring.support.PlsqlApiRegistrar;

/**
 * Включает поиск интерфейсов {@link PlsqlApi} и регистрирует реализацию для каждого из них,
 * как {@code @EnableJdbcRepositories} в Spring Data.
 *
 * <p>Ставится на класс конфигурации Spring ({@code @Configuration}). Через {@code @Import}
 * аннотация подключает {@link PlsqlApiRegistrar}: при разборе конфигурации он сканирует
 * пакеты и регистрирует бины, то есть добавляет в контекст описания будущих бинов
 * ({@code BeanDefinition}) ещё до того, как какой-либо бин создан. Каждый интерфейс
 * описывается как {@code PlsqlApiFactoryBean} — FactoryBean, то есть бин-фабрика, которую
 * Spring просит изготовить настоящий объект; в контексте виден уже готовый объект с типом
 * интерфейса, и его можно внедрять через {@code @Autowired}.
 *
 * <p>Со Spring Boot аннотация не нужна: автоконфигурация (конфигурация, которую Boot
 * подключает сам, найдя библиотеку в classpath) сканирует пакет приложения. Если же
 * {@code @EnablePlsqlApis} всё-таки стоит, автоконфигурация своё сканирование пропускает.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(PlsqlApiRegistrar.class)
public @interface EnablePlsqlApis {

    /**
     * Возвращает пакеты, в которых ищутся интерфейсы {@link PlsqlApi}; вложенные пакеты тоже
     * просматриваются.
     *
     * <p>Пустой массив означает пакет класса, на котором стоит аннотация.
     *
     * @return имена пакетов для сканирования
     */
    String[] basePackages() default {};

    /**
     * Возвращает имя бина {@code DataSource} (пула соединений с базой), на котором строится
     * фабрика реализаций.
     *
     * <p>Используется, если {@link #factoryRef()} пуст. Единственный бин {@code PlsqlApiFactory}
     * из контекста берётся, только когда здесь стоит значение по умолчанию или эта фабрика
     * работает именно с этим {@code DataSource}; иначе одна общая фабрика строится на этом
     * {@code DataSource} для всех найденных интерфейсов. Так вторая база не уходит молча в
     * фабрику первой.
     *
     * @return имя бина {@code DataSource}; по умолчанию {@code dataSource}
     */
    String dataSourceRef() default "dataSource";

    /**
     * Возвращает имя бина {@code PlsqlApiFactory}, которым создаются реализации; нужно, когда
     * в одном приложении несколько баз.
     *
     * <p>Пустая строка означает выбрать фабрику по {@link #dataSourceRef()}: единственный бин
     * {@code PlsqlApiFactory} из контекста, если он подходит к этому {@code DataSource}, иначе
     * общую фабрику на нём.
     *
     * @return имя бина {@code PlsqlApiFactory} или пустая строка
     */
    String factoryRef() default "";
}
