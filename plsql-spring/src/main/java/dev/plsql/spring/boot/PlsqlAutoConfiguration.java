package dev.plsql.spring.boot;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.support.PlsqlApiFactoryBean;
import dev.plsql.spring.support.PlsqlApiRegistrar;
import io.micrometer.observation.ObservationRegistry;

/**
 * Настраивает библиотеку в приложении Spring Boot без каких-либо аннотаций со стороны пользователя.
 *
 * <p>Это автоконфигурация: класс конфигурации, который Spring Boot подключает сам, потому что он
 * перечислен в файле
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * внутри jar-файла библиотеки. Она создаёт один {@link PlsqlApiFactory} на {@code DataSource}
 * приложения и регистрирует бином каждый интерфейс {@code @PlsqlApi} из пакетов приложения — так
 * же, как Boot находит репозитории Spring Data без {@code @Enable...}.
 *
 * <p>Включается, только если в classpath есть драйвер Oracle
 * ({@code oracle.jdbc.OracleConnection}). Обрабатывается после автоконфигурации
 * {@code DataSource}, чтобы пул соединений к этому моменту уже был объявлен. Настройки
 * {@code plsql.*} читаются в {@link PlsqlProperties}.
 */
// В Boot 4 DataSourceAutoConfiguration переехала в другой пакет; указаны оба имени, чтобы и на 3.x
// DataSource создавался раньше.
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@ConditionalOnClass(oracle.jdbc.OracleConnection.class)
@EnableConfigurationProperties(PlsqlProperties.class)
@Import(PlsqlAutoConfiguration.Registrar.class)
public class PlsqlAutoConfiguration {

    /**
     * Создаёт бин {@link PlsqlApiFactory} — фабрику, которая строит реализации интерфейсов
     * {@code @PlsqlApi} на {@code DataSource} приложения.
     *
     * <p>Бин создаётся, только если пользователь не объявил свой {@code PlsqlApiFactory}
     * ({@code @ConditionalOnMissingBean}) и в контексте есть ровно один {@code DataSource} или
     * один из нескольких помечен как основной ({@code @ConditionalOnSingleCandidate}). Иначе
     * фабрики объявляет само приложение, а без них {@link PlsqlApiFactoryBean} строит свою
     * фабрику на бине {@code DataSource} с именем {@code dataSource}.
     *
     * <p>В фабрику переносятся настройки {@code plsql.*}. Бины {@link ArgumentDefaults} и
     * {@link SignatureSource} необязательны: если такой бин один (или один из нескольких помечен
     * как основной), он подключается; если их несколько равноправных, это ошибка при старте, а
     * не молчаливый отказ от всех.
     *
     * <p>Если политика кодировки {@code FAIL} и {@code plsql.database-charset} не задан, фабрика
     * при создании обращается к базе, чтобы прочитать NLS_CHARACTERSET (кодировку базы).
     *
     * @param dataSource пул соединений приложения
     * @param props      настройки {@code plsql.*}
     * @param defaults   необязательный бин со значениями аргументов, которых нет в методах Java
     *                   (например, {@code NTENANT})
     * @param signatures необязательный источник сигнатур подпрограмм вместо словаря Oracle
     * @return готовая фабрика
     * @throws IllegalArgumentException если {@code plsql.index-table-max-length} меньше 1
     * @throws org.springframework.beans.factory.NoUniqueBeanDefinitionException если бинов
     *         {@code ArgumentDefaults} или {@code SignatureSource} несколько и ни один не основной
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DataSource.class)
    PlsqlApiFactory plsqlApiFactory(DataSource dataSource, PlsqlProperties props,
                                    ObjectProvider<ArgumentDefaults> defaults,
                                    ObjectProvider<SignatureSource> signatures,
                                    ObjectProvider<ObservationRegistry> observations) {
        PlsqlApiFactory.Builder b = props.applyTo(PlsqlApiFactory.builder(dataSource));
        defaults.ifAvailable(b::argumentDefaults);
        signatures.ifAvailable(b::signatureSource);
        observations.ifAvailable(b::observationRegistry);
        return b.build();
    }

    /**
     * Регистрирует бины для интерфейсов {@code @PlsqlApi} из пакетов приложения Spring Boot.
     *
     * <p>Реализует {@code ImportBeanDefinitionRegistrar} — точку расширения Spring, которую
     * вызывают при разборе конфигурации, чтобы добавить описания бинов ({@code BeanDefinition})
     * до создания самих бинов. Подключается через {@code @Import} на
     * {@link PlsqlAutoConfiguration}. Интерфейсы {@code ...Aware} нужны, чтобы Spring перед
     * вызовом передал сюда фабрику бинов, загрузчик ресурсов и окружение.
     */
    static class Registrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, ResourceLoaderAware, EnvironmentAware {

        private BeanFactory beanFactory;
        private ResourceLoader resourceLoader;
        private Environment environment;

        /**
         * Запоминает фабрику бинов контекста; вызывается Spring автоматически.
         *
         * <p>Нужна, чтобы узнать пакеты приложения ({@code AutoConfigurationPackages}).
         *
         * @param beanFactory фабрика бинов текущего контекста
         */
        @Override
        public void setBeanFactory(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        /**
         * Запоминает загрузчик ресурсов; вызывается Spring автоматически.
         *
         * <p>Через него сканер читает файлы классов и получает загрузчик классов приложения.
         *
         * @param resourceLoader загрузчик ресурсов контекста
         */
        @Override
        public void setResourceLoader(ResourceLoader resourceLoader) {
            this.resourceLoader = resourceLoader;
        }

        /**
         * Запоминает окружение (свойства и профили); вызывается Spring автоматически.
         *
         * <p>Окружение передаётся сканеру классов.
         *
         * @param environment окружение контекста
         */
        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        /**
         * Сканирует пакеты приложения и регистрирует {@link PlsqlApiFactoryBean} для каждого
         * найденного интерфейса {@code @PlsqlApi}.
         *
         * <p>Пакеты приложения — это пакет класса с {@code @SpringBootApplication} и другие,
         * записанные через {@code @AutoConfigurationPackage}. Если их нет (например, в тестовом
         * контексте без Boot-приложения), ничего не делает. Если в реестре уже есть хотя бы один
         * {@code PlsqlApiFactoryBean}, значит, приложение само включило {@code @EnablePlsqlApis}
         * (пользовательская конфигурация разбирается раньше автоконфигурации), и повторное
         * сканирование не выполняется. Фабрики регистрируются с бином {@code DataSource} по имени
         * {@code dataSource} и без явного {@code factoryRef}.
         *
         * @param metadata метаданные класса, который импортировал этот регистратор (здесь это
         *                 {@link PlsqlAutoConfiguration}; не используются)
         * @param registry реестр описаний бинов, куда добавляются новые бины
         */
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            if (!AutoConfigurationPackages.has(beanFactory)) {
                return;
            }
            if (registry instanceof org.springframework.beans.factory.ListableBeanFactory lbf
                    && lbf.getBeanNamesForType(PlsqlApiFactoryBean.class, false, false).length > 0) {
                return; // это уже сделала @EnablePlsqlApis
            }
            List<String> packages = AutoConfigurationPackages.get(beanFactory);
            PlsqlApiRegistrar.register(registry, packages, "", "", resourceLoader, environment);
        }
    }
}
