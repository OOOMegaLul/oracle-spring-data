package dev.plsql.spring.support;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

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
 *       нескольких);</li>
 *   <li>иначе фабрика, построенная на бине {@code DataSource} с именем {@code dataSourceRef}
 *       вместе с бинами {@link ArgumentDefaults} и {@link SignatureSource}, если они есть в
 *       контексте.</li>
 * </ul>
 *
 * @param <T> тип интерфейса {@code @PlsqlApi}, реализацию которого создаёт этот бин
 */
public class PlsqlApiFactoryBean<T> implements FactoryBean<T>, BeanFactoryAware, InitializingBean {

    private final Class<T> apiInterface;
    private String dataSourceRef = "dataSource";
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
     * бин {@code PlsqlApiFactory} (или основной из нескольких), а если такого нет или их
     * несколько без основного — общая фабрика из {@link #sharedFactory()}.
     *
     * @return фабрика реализаций
     */
    private PlsqlApiFactory factory() {
        if (!factoryRef.isEmpty()) {
            return beanFactory.getBean(factoryRef, PlsqlApiFactory.class);
        }
        return beanFactory.getBeanProvider(PlsqlApiFactory.class).getIfUnique(this::sharedFactory);
    }

    /**
     * Возвращает общую фабрику для {@code DataSource} с именем {@code dataSourceRef}, создавая её
     * при первом обращении.
     *
     * <p>Одна фабрика на {@code DataSource} для всех интерфейсов контекста; её регистрирует как
     * singleton (единственный экземпляр в контексте) первый фабричный бин, которому она понадобилась,
     * под именем {@code plsqlApiFactory#<имя DataSource>}. Строить отдельную фабрику на каждый
     * интерфейс означало бы повторять для каждого сборку {@link PlsqlRuntime} и запрос
     * NLS_CHARACTERSET.
     *
     * <p>Проверка и регистрация идут под общей блокировкой реестра singleton-бинов, чтобы два бина,
     * создаваемые параллельно, не построили две фабрики. Если фабрика бинов не
     * {@link ConfigurableBeanFactory} (зарегистрировать singleton нельзя), фабрика строится заново
     * при каждом обращении.
     *
     * @return общая фабрика для {@code dataSourceRef}
     */
    private PlsqlApiFactory sharedFactory() {
        String name = "plsqlApiFactory#" + dataSourceRef;
        if (beanFactory instanceof ConfigurableBeanFactory cbf) {
            synchronized (cbf.getSingletonMutex()) {
                if (cbf.containsSingleton(name)) {
                    return (PlsqlApiFactory) cbf.getSingleton(name);
                }
                PlsqlApiFactory f = build();
                cbf.registerSingleton(name, f);
                return f;
            }
        }
        return build();
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
