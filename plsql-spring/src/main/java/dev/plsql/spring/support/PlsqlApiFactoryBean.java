package dev.plsql.spring.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import dev.plsql.spring.PlsqlApiFactory;
import dev.plsql.spring.call.ArgumentDefaults;
import dev.plsql.spring.meta.SignatureSource;

/**
 * Создаёт реализацию одного интерфейса {@code @PlsqlApi}; аналог
 * {@code RepositoryFactoryBeanSupport} из Spring Data. Регистрируется {@link PlsqlApiRegistrar}
 * (или автоконфигурацией Spring Boot) — по одному экземпляру на каждый найденный интерфейс.
 *
 * <p>{@link FactoryBean} — особый вид бина Spring, который служит фабрикой: по его имени
 * контекст отдаёт не его самого, а изготовленный им объект. Когда кто-то запрашивает бин по
 * имени или внедряет интерфейс через {@code @Autowired}, Spring вызывает {@link #getObject()} и
 * отдаёт результат — здесь это прокси-объект (объект, созданный на лету, который реализует
 * интерфейс и переадресует вызовы методов в PL/SQL).
 *
 * <p>Порядок жизни бина: Spring вызывает конструктор с классом интерфейса, заполняет свойства
 * {@code dataSourceRef} и {@code factoryRef}, передаёт фабрику бинов через
 * {@link #setBeanFactory(BeanFactory)} и затем вызывает {@link #afterPropertiesSet()}, где и
 * создаётся реализация.
 *
 * <p>Какой {@link PlsqlApiFactory} используется:
 * <ul>
 *   <li>бин с именем из {@code factoryRef}, если оно задано;</li>
 *   <li>иначе единственный бин {@link PlsqlApiFactory} в контексте (или основной из
 *       нескольких) — если {@code dataSourceRef} оставлен по умолчанию или эта фабрика работает
 *       именно с бином {@code dataSourceRef} (напрямую или через обёртки вроде
 *       {@code SessionContextDataSource});</li>
 *   <li>иначе общая фабрика, построенная на бине {@code DataSource} с именем
 *       {@code dataSourceRef} вместе с бинами {@link ArgumentDefaults} и {@link SignatureSource},
 *       если они есть в контексте.</li>
 * </ul>
 * Так явно указанный {@code dataSourceRef} никогда не уходит молча в чужую базу.
 *
 * @param <T> тип интерфейса {@code @PlsqlApi}, реализацию которого создаёт этот бин
 */
public class PlsqlApiFactoryBean<T> implements FactoryBean<T>, BeanFactoryAware, InitializingBean {

    /**
     * Имя бина {@code DataSource} по умолчанию, как у Spring Boot.
     */
    static final String DEFAULT_DATA_SOURCE = "dataSource";

    /**
     * Имя служебного singleton-бина, в котором лежат общие фабрики по именам {@code DataSource}.
     */
    static final String SHARED_FACTORIES = "plsqlApiFactory#shared";

    private final Class<T> apiInterface;
    private String dataSourceRef = DEFAULT_DATA_SOURCE;
    private String factoryRef = "";
    private BeanFactory beanFactory;
    private T instance;

    /**
     * Создаёт фабричный бин для одного интерфейса.
     *
     * <p>Класс интерфейса передаёт {@link PlsqlApiRegistrar} как аргумент конструктора в
     * описании бина.
     *
     * @param apiInterface интерфейс с аннотацией {@code @PlsqlApi}
     */
    public PlsqlApiFactoryBean(Class<T> apiInterface) {
        this.apiInterface = apiInterface;
    }

    /**
     * Задаёт имя бина {@code DataSource}, на котором строится фабрика.
     *
     * <p>Используется, только если {@code factoryRef} пуст и в контексте нет единственного
     * (или основного) бина {@code PlsqlApiFactory}.
     *
     * @param dataSourceRef имя бина {@code DataSource}; по умолчанию {@code dataSource}
     */
    public void setDataSourceRef(String dataSourceRef) {
        this.dataSourceRef = dataSourceRef;
    }

    /**
     * Задаёт имя бина {@code PlsqlApiFactory}, которым создаётся реализация.
     *
     * <p>Нужно, когда в приложении несколько баз и, соответственно, несколько фабрик.
     *
     * @param factoryRef имя бина {@code PlsqlApiFactory}; пустая строка — выбрать фабрику
     *                   автоматически
     */
    public void setFactoryRef(String factoryRef) {
        this.factoryRef = factoryRef;
    }

    /**
     * Запоминает фабрику бинов контекста; вызывается Spring автоматически, потому что класс
     * реализует {@link BeanFactoryAware}.
     *
     * <p>Через неё бин потом находит {@code PlsqlApiFactory}, {@code DataSource} и прочие
     * нужные бины.
     *
     * @param beanFactory фабрика бинов текущего контекста
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Создаёт реализацию интерфейса; вызывается Spring после установки всех свойств, потому что
     * класс реализует {@link InitializingBean}.
     *
     * <p>Здесь читаются сигнатуры подпрограмм из базы и каждый метод интерфейса сверяется с ними.
     * Поэтому расхождение между интерфейсом и базой останавливает старт приложения, а не
     * проявляется при первом вызове.
     *
     * @throws IllegalStateException если методы интерфейса не совпадают с базой; в сообщении
     *                               перечислены все найденные проблемы
     */
    @Override
    public void afterPropertiesSet() {
        instance = factory().create(apiInterface);
    }

    /**
     * Выбирает {@link PlsqlApiFactory}, которым будет создана реализация.
     *
     * <p>Если задан {@code factoryRef}, берётся бин с этим именем. Иначе берётся единственный
     * бин {@code PlsqlApiFactory} (или основной из нескольких), но только если он подходит к
     * {@code dataSourceRef} (см. описание класса). В остальных случаях — общая фабрика из
     * {@link #sharedFactory()}.
     *
     * @return фабрика реализаций
     */
    PlsqlApiFactory factory() {
        if (!factoryRef.isEmpty()) {
            return beanFactory.getBean(factoryRef, PlsqlApiFactory.class);
        }
        PlsqlApiFactory declared = beanFactory.getBeanProvider(PlsqlApiFactory.class).getIfUnique();
        if (declared != null && (DEFAULT_DATA_SOURCE.equals(dataSourceRef)
                || uses(declared, beanFactory.getBean(dataSourceRef, DataSource.class)))) {
            return declared;
        }
        return sharedFactory();
    }

    /**
     * Проверяет, работает ли фабрика с этим {@code DataSource}: напрямую или через обёртки
     * {@link DelegatingDataSource} (к ним относится и {@code SessionContextDataSource}).
     *
     * @param factory фабрика из контекста
     * @param wanted  бин {@code dataSourceRef}
     * @return {@code true}, если {@code wanted} встречается в цепочке обёрток фабрики
     */
    private static boolean uses(PlsqlApiFactory factory, DataSource wanted) {
        DataSource ds = factory.runtime().dataSource();
        for (int depth = 0; ds != null && depth < 10; depth++) {
            if (ds == wanted) {
                return true;
            }
            ds = ds instanceof DelegatingDataSource d ? d.getTargetDataSource() : null;
        }
        return false;
    }

    /**
     * Возвращает общую фабрику для {@code DataSource} с именем {@code dataSourceRef}, создавая её
     * при первом обращении.
     *
     * <p>Одна фабрика на {@code DataSource} для всех интерфейсов контекста. Фабрики лежат в
     * служебном singleton-бине (единственном экземпляре в контексте) {@value #SHARED_FACTORIES} —
     * словаре «имя {@code DataSource} — фабрика», а не отдельными бинами {@code PlsqlApiFactory}:
     * иначе фабрика первой базы оказалась бы «единственной» и досталась бы интерфейсам второй.
     * Строить отдельную фабрику на каждый интерфейс означало бы повторять для каждого сборку
     * {@link PlsqlRuntime} и запрос NLS_CHARACTERSET.
     *
     * <p>Служебный бин регистрируется под общей блокировкой реестра singleton-бинов, а фабрика
     * строится один раз ({@code computeIfAbsent}), даже если бины создаются параллельно. Если
     * фабрика бинов не {@link ConfigurableBeanFactory} (зарегистрировать singleton нельзя),
     * фабрика строится заново при каждом обращении.
     *
     * @return общая фабрика для {@code dataSourceRef}
     */
    @SuppressWarnings("unchecked")
    private PlsqlApiFactory sharedFactory() {
        if (!(beanFactory instanceof ConfigurableBeanFactory cbf)) {
            return build();
        }
        Map<String, PlsqlApiFactory> shared;
        synchronized (cbf.getSingletonMutex()) {
            if (!cbf.containsSingleton(SHARED_FACTORIES)) {
                cbf.registerSingleton(SHARED_FACTORIES, new ConcurrentHashMap<String, PlsqlApiFactory>());
            }
            shared = (Map<String, PlsqlApiFactory>) cbf.getSingleton(SHARED_FACTORIES);
        }
        return shared.computeIfAbsent(dataSourceRef, ref -> build());
    }

    /**
     * Строит новую фабрику на бине {@code DataSource} с именем {@code dataSourceRef}.
     *
     * <p>Бины {@link ArgumentDefaults} и {@link SignatureSource} подключаются, только если такой
     * бин один (или один из нескольких помечен как основной). Остальные параметры фабрики
     * остаются по умолчанию: настройки {@code plsql.*} из Spring Boot сюда не попадают.
     *
     * @return новая фабрика
     */
    private PlsqlApiFactory build() {
        PlsqlApiFactory.Builder b = PlsqlApiFactory.builder(beanFactory.getBean(dataSourceRef, DataSource.class));
        beanFactory.getBeanProvider(ArgumentDefaults.class).ifUnique(b::argumentDefaults);
        beanFactory.getBeanProvider(SignatureSource.class).ifUnique(b::signatureSource);
        return b.build();
    }

    /**
     * Возвращает готовую реализацию интерфейса — именно этот объект Spring внедряет вместо
     * самого фабричного бина.
     *
     * <p>Реализация создаётся один раз в {@link #afterPropertiesSet()}, поэтому метод всегда
     * возвращает один и тот же объект.
     *
     * @return реализация интерфейса {@code @PlsqlApi}
     */
    @Override
    public T getObject() {
        return instance;
    }

    /**
     * Возвращает тип объекта, который производит этот фабричный бин, — интерфейс {@code @PlsqlApi}.
     *
     * <p>По нему Spring понимает, что бин подходит для внедрения по типу интерфейса.
     *
     * @return класс интерфейса
     */
    @Override
    public Class<?> getObjectType() {
        return apiInterface;
    }
}
