package dev.plsql.spring.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import dev.plsql.spring.annotation.EnablePlsqlApis;
import dev.plsql.spring.annotation.PlsqlApi;

/**
 * Находит интерфейсы {@code @PlsqlApi} и регистрирует для каждого {@link PlsqlApiFactoryBean}.
 *
 * <p>Регистрация бинов — это добавление в контекст Spring описания будущего бина
 * ({@code BeanDefinition}: какой класс создать, какие аргументы и свойства ему передать) ещё до
 * того, как создан хоть один бин. Сами объекты Spring создаст позже по этим описаниям.
 *
 * <p>Класс реализует {@link ImportBeanDefinitionRegistrar} — точку расширения Spring для такой
 * регистрации. Его подключает {@link EnablePlsqlApis} через {@code @Import}; статический метод
 * {@link #register} вызывает также автоконфигурация Spring Boot.
 */
public class PlsqlApiRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {

    private ResourceLoader resourceLoader;
    private Environment environment;

    /**
     * Запоминает загрузчик ресурсов; вызывается Spring автоматически, потому что класс реализует
     * {@link ResourceLoaderAware}.
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
     * Запоминает окружение (свойства и профили); вызывается Spring автоматически, потому что
     * класс реализует {@link EnvironmentAware}.
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
     * Читает параметры {@link EnablePlsqlApis} с класса конфигурации и регистрирует бины для
     * найденных интерфейсов.
     *
     * <p>Вызывается Spring при разборе класса конфигурации, на котором стоит
     * {@code @EnablePlsqlApis}. Если {@code basePackages} пуст, сканируется пакет этого класса.
     * Если аннотации на классе нет (регистратор подключён через {@code @Import} напрямую),
     * берутся значения по умолчанию: {@code DataSource} с именем {@code dataSource}, без
     * {@code factoryRef}, пакет класса конфигурации.
     *
     * @param metadata метаданные класса конфигурации, который подключил регистратор
     * @param registry реестр описаний бинов, куда добавляются новые бины
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(EnablePlsqlApis.class.getName());
        List<String> packages = new ArrayList<>();
        String dataSourceRef = "dataSource";
        String factoryRef = "";
        if (attrs != null) {
            packages.addAll(Arrays.asList((String[]) attrs.get("basePackages")));
            dataSourceRef = (String) attrs.get("dataSourceRef");
            factoryRef = (String) attrs.get("factoryRef");
        }
        if (packages.isEmpty()) {
            packages.add(ClassUtils.getPackageName(metadata.getClassName()));
        }
        register(registry, packages, dataSourceRef, factoryRef, resourceLoader, environment);
    }

    /**
     * Регистрирует по одному фабричному бину на каждый интерфейс {@code @PlsqlApi}, найденный в
     * {@code packages}.
     *
     * <p>Пакеты сканируются вместе с вложенными. Имя бина — простое имя интерфейса с маленькой
     * буквы ({@code TenantContext} даёт {@code tenantContext}). Если бин с таким именем уже
     * есть и описывает тот же интерфейс (интерфейс попал сюда через два сканируемых пакета),
     * повторная регистрация пропускается. Если это другой бин (два интерфейса с одинаковым
     * простым именем), используется полное имя интерфейса с пакетом.
     *
     * <p>Каждому описанию бина передаются класс интерфейса (аргумент конструктора),
     * {@code dataSourceRef} и {@code factoryRef} (свойства) и атрибут
     * {@code FactoryBean.OBJECT_TYPE_ATTRIBUTE} с типом интерфейса.
     *
     * @param registry       реестр описаний бинов, куда добавляются новые бины
     * @param packages       пакеты для сканирования
     * @param dataSourceRef  имя бина {@code DataSource} для фабрики, если она строится сама
     * @param factoryRef     имя бина {@code PlsqlApiFactory} или пустая строка
     * @param resourceLoader загрузчик ресурсов для сканера; {@code null} — загрузчик классов
     *                       по умолчанию
     * @param environment    окружение для сканера
     */
    public static void register(BeanDefinitionRegistry registry, List<String> packages, String dataSourceRef,
                                String factoryRef, ResourceLoader resourceLoader, Environment environment) {
        // Сканер классов Spring без стандартных фильтров (@Component и т.п.): ищет только
        // интерфейсы с @PlsqlApi.
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            /**
             * Проверяет, подходит ли найденный класс: нужен интерфейс, который можно загрузить
             * отдельно (верхнего уровня или статический вложенный).
             *
             * <p>Стандартная проверка сканера пропускает только конкретные классы, а здесь нужны,
             * наоборот, интерфейсы.
             *
             * @param bd описание найденного класса с его метаданными
             * @return {@code true}, если класс — самостоятельный интерфейс
             */
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition bd) {
                return bd.getMetadata().isInterface() && bd.getMetadata().isIndependent();
            }
        };
        if (resourceLoader != null) {
            scanner.setResourceLoader(resourceLoader);
        }
        scanner.addIncludeFilter(new AnnotationTypeFilter(PlsqlApi.class));
        ClassLoader cl = resourceLoader == null ? ClassUtils.getDefaultClassLoader() : resourceLoader.getClassLoader();
        for (String pkg : packages) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(pkg)) {
                Class<?> api = ClassUtils.resolveClassName(candidate.getBeanClassName(), cl);
                String beanName = ClassUtils.getShortNameAsProperty(api);
                if (registry.containsBeanDefinition(beanName)) {
                    BeanDefinition existing = registry.getBeanDefinition(beanName);
                    if (api.equals(existing.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE))) {
                        continue; // тот же интерфейс, найденный через два сканируемых пакета
                    }
                    beanName = api.getName(); // два интерфейса с одинаковым простым именем
                }
                BeanDefinitionBuilder b = BeanDefinitionBuilder.genericBeanDefinition(PlsqlApiFactoryBean.class)
                        .addConstructorArgValue(api)
                        .addPropertyValue("dataSourceRef", dataSourceRef)
                        .addPropertyValue("factoryRef", factoryRef);
                AbstractBeanDefinition bd = b.getBeanDefinition();
                // Позволяет @Autowired найти бин по типу интерфейса ещё до того, как фабрика отработала.
                bd.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, api);
                registry.registerBeanDefinition(beanName, bd);
            }
        }
    }
}
