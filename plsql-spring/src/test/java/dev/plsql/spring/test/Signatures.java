package dev.plsql.spring.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SignatureSource;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Фикстуры сигнатур для юнит-тестов: подпрограммы описываются в том виде, в каком их
 * описывает словарь Oracle {@code ALL_ARGUMENTS}. Так планировщик и прокси можно проверять
 * без базы данных.
 *
 * <p>Построитель собирает одну подпрограмму (процедуру или функцию), статические методы
 * собирают отдельные аргументы сложных типов:
 * <pre>{@code
 * Signatures.proc("PKG", "P_X").in("NTENANT", "NUMBER").out("NRN", "NUMBER").build()
 * }</pre>
 */
public final class Signatures {

    /** Владелец (схема) всех подпрограмм фикстуры; поэтому имена в блоках начинаются с {@code APP.}. */
    public static final String OWNER = "APP";

    private final String pkg;
    private final String name;
    private final ArgumentInfo returnValue;
    private final List<ArgumentInfo> args = new ArrayList<>();
    private String overload;

    /**
     * Создаёт построитель; снаружи используются {@link #proc} и {@link #func}.
     *
     * @param pkg         имя пакета или {@code null} для автономной подпрограммы
     * @param name        имя процедуры или функции
     * @param returnValue описание возвращаемого значения; {@code null} для процедуры
     */
    private Signatures(String pkg, String name, ArgumentInfo returnValue) {
        this.pkg = pkg;
        this.name = name;
        this.returnValue = returnValue;
    }

    /**
     * Начинает описание процедуры, то есть подпрограммы без возвращаемого значения.
     *
     * @param pkg  имя пакета или {@code null} для автономной процедуры
     * @param name имя процедуры
     * @return построитель, в который добавляются аргументы
     */
    public static Signatures proc(String pkg, String name) {
        return new Signatures(pkg, name, null);
    }

    /**
     * Начинает описание функции, возвращающей значение простого типа.
     *
     * @param pkg        имя пакета или {@code null} для автономной функции
     * @param name       имя функции
     * @param returnType тип результата так, как его пишет {@code ALL_ARGUMENTS.DATA_TYPE}:
     *                   {@code NUMBER}, {@code PL/SQL BOOLEAN} и т.п.
     * @return построитель, в который добавляются аргументы
     */
    public static Signatures func(String pkg, String name, String returnType) {
        return new Signatures(pkg, name, arg(null, returnType, "OUT"));
    }

    /**
     * Начинает описание функции со сложным возвращаемым значением ({@code XMLTYPE},
     * index-by таблица и т.п.), описанным готовым {@link ArgumentInfo}.
     *
     * @param pkg         имя пакета или {@code null} для автономной функции
     * @param name        имя функции
     * @param returnValue описание результата; у возвращаемого значения имя {@code null}
     * @return построитель, в который добавляются аргументы
     */
    public static Signatures func(String pkg, String name, ArgumentInfo returnValue) {
        return new Signatures(pkg, name, returnValue);
    }

    /**
     * Задаёт номер перегрузки, как его пишет {@code ALL_ARGUMENTS.OVERLOAD}.
     *
     * @param n номер перегрузки строкой: {@code "1"}, {@code "2"}...
     * @return этот же построитель
     */
    public Signatures overload(String n) {
        this.overload = n;
        return this;
    }

    /**
     * Добавляет входной ({@code IN}) аргумент без значения по умолчанию.
     *
     * @param argName имя аргумента, например {@code NTENANT}
     * @param type    тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @return этот же построитель
     */
    public Signatures in(String argName, String type) {
        return add(arg(argName, type, "IN"));
    }

    /**
     * Добавляет входной аргумент, объявленный с {@code DEFAULT}: такой аргумент можно не
     * передавать, и планировщик его пропускает.
     *
     * @param argName имя аргумента
     * @param type    тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @return этот же построитель
     */
    public Signatures inDefault(String argName, String type) {
        return add(new ArgumentInfo(argName, 0, 0, type, null, "IN", true, null, null, null, null));
    }

    /**
     * Добавляет выходной ({@code OUT}) аргумент.
     *
     * @param argName имя аргумента
     * @param type    тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @return этот же построитель
     */
    public Signatures out(String argName, String type) {
        return add(arg(argName, type, "OUT"));
    }

    /**
     * Добавляет аргумент {@code IN OUT}; в словаре его направление записано как {@code IN/OUT}.
     *
     * @param argName имя аргумента
     * @param type    тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @return этот же построитель
     */
    public Signatures inOut(String argName, String type) {
        return add(arg(argName, type, "IN/OUT"));
    }

    /**
     * Добавляет готовый аргумент как есть: запись, таблицу, {@code XMLTYPE} и т.п.
     *
     * @param a описание аргумента, например из {@link #record} или {@link #xml}
     * @return этот же построитель
     */
    public Signatures add(ArgumentInfo a) {
        args.add(a);
        return this;
    }

    /**
     * Собирает сигнатуру с владельцем {@link #OWNER}. Аргументы получают позиции 1..n в
     * порядке добавления, как {@code ALL_ARGUMENTS.POSITION}; остальные поля копируются
     * без изменений.
     *
     * @return описание подпрограммы
     */
    public SubprogramInfo build() {
        List<ArgumentInfo> positioned = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            ArgumentInfo a = args.get(i);
            positioned.add(new ArgumentInfo(a.name(), i + 1, a.dataLevel(), a.dataType(), a.plsType(), a.inOut(),
                    a.defaulted(), a.typeOwner(), a.typeName(), a.typeSubname(), a.children()));
        }
        return new SubprogramInfo(OWNER, pkg, name, overload, returnValue, positioned);
    }

    // ------------------------------------------------------------------ аргументы

    /**
     * Аргумент верхнего уровня ({@code DATA_LEVEL} 0) простого типа, без {@code DEFAULT} и
     * без имени типа. Позиция 0; у аргументов её заменяет {@link #build()}, у возвращаемого
     * значения функции она так и остаётся нулевой.
     *
     * @param name  имя аргумента; {@code null} для возвращаемого значения
     * @param type  тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @param inOut направление: {@code IN}, {@code OUT} или {@code IN/OUT}
     * @return описание аргумента
     */
    public static ArgumentInfo arg(String name, String type, String inOut) {
        return new ArgumentInfo(name, 0, 0, type, null, inOut, false, null, null, null, null);
    }

    /**
     * Запись PL/SQL ({@code RECORD}), объявленная в пакете: тип задают
     * {@code TYPE_OWNER.TYPE_NAME.TYPE_SUBNAME}, то есть {@code APP.<pkg>.<type>}.
     *
     * @param name   имя аргумента; {@code null} для возвращаемого значения
     * @param inOut  направление аргумента; его же получают поля
     * @param pkg    пакет, где объявлен тип записи
     * @param type   имя типа записи в пакете
     * @param fields поля записи, см. {@link #field}
     * @return описание аргумента-записи
     */
    public static ArgumentInfo record(String name, String inOut, String pkg, String type, ArgumentInfo... fields) {
        return new ArgumentInfo(name, 0, 0, "PL/SQL RECORD", null, inOut, false, OWNER, pkg, type,
                children(inOut, fields));
    }

    /**
     * Аргумент {@code %ROWTYPE} в том виде, в каком его оставляет {@code DictionaryReader}:
     * {@code TYPE_NAME = OWNER.TABLE}, без {@code TYPE_OWNER} и без {@code TYPE_SUBNAME}.
     * По этому признаку планировщик объявляет переменную как {@code APP.<table>%ROWTYPE}.
     *
     * @param name   имя аргумента
     * @param inOut  направление аргумента; его же получают поля
     * @param table  таблица, по строке которой объявлен тип
     * @param fields колонки таблицы как поля записи, см. {@link #field}
     * @return описание аргумента-записи
     */
    public static ArgumentInfo rowtype(String name, String inOut, String table, ArgumentInfo... fields) {
        return new ArgumentInfo(name, 0, 0, "PL/SQL RECORD", null, inOut, false, null, OWNER + "." + table, null,
                children(inOut, fields));
    }

    /**
     * Поле записи или атрибут объектного типа ({@code DATA_LEVEL} 1). Направление здесь
     * условное: при добавлении в запись или объект его заменяет направление родителя.
     *
     * @param name имя поля
     * @param type тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @return описание поля
     */
    public static ArgumentInfo field(String name, String type) {
        return new ArgumentInfo(name, 0, 1, type, null, "IN", false, null, null, null, null);
    }

    /**
     * Index-by таблица PL/SQL ({@code PL/SQL TABLE}) типа {@code APP.PKG.T_TAB} с одним
     * дочерним элементом, который описывает тип элемента.
     *
     * @param name        имя аргумента; {@code null} для возвращаемого значения
     * @param inOut       направление аргумента
     * @param elementType тип элемента, например {@code NUMBER}, {@code DATE} или {@code PL/SQL RECORD}
     * @return описание аргумента-таблицы
     */
    public static ArgumentInfo indexTable(String name, String inOut, String elementType) {
        return new ArgumentInfo(name, 0, 0, "PL/SQL TABLE", null, inOut, false, OWNER, "PKG", "T_TAB",
                List.of(new ArgumentInfo(null, 1, 1, elementType, null, inOut, false, null, null, null, null)));
    }

    /**
     * Объектный тип SQL ({@code OBJECT}) {@code APP.<type>} с атрибутами.
     *
     * @param name  имя аргумента
     * @param inOut направление аргумента; его же получают атрибуты
     * @param type  имя объектного типа в схеме {@code APP}
     * @param attrs атрибуты типа, см. {@link #field}
     * @return описание аргумента объектного типа
     */
    public static ArgumentInfo object(String name, String inOut, String type, ArgumentInfo... attrs) {
        return new ArgumentInfo(name, 0, 0, "OBJECT", null, inOut, false, OWNER, type, null, children(inOut, attrs));
    }

    /**
     * Аргумент {@code XMLTYPE}: {@code DATA_TYPE = OPAQUE/XMLTYPE}, тип {@code PUBLIC.XMLTYPE}.
     *
     * @param name  имя аргумента; {@code null} для возвращаемого значения
     * @param inOut направление аргумента
     * @return описание аргумента
     */
    public static ArgumentInfo xml(String name, String inOut) {
        return new ArgumentInfo(name, 0, 0, "OPAQUE/XMLTYPE", null, inOut, false, "PUBLIC", "XMLTYPE", null, null);
    }

    /**
     * Превращает поля в дочерние строки аргумента: {@code DATA_LEVEL} 1, направление
     * родителя, без {@code DEFAULT}; позиция, тип и вложенные элементы сохраняются.
     *
     * @param inOut  направление родительского аргумента
     * @param fields поля или атрибуты
     * @return изменяемый список дочерних элементов
     */
    private static List<ArgumentInfo> children(String inOut, ArgumentInfo... fields) {
        return new ArrayList<>(Arrays.stream(fields).map(f -> new ArgumentInfo(f.name(), f.position(), 1, f.dataType(),
                f.plsType(), inOut, false, f.typeOwner(), f.typeName(), f.typeSubname(), f.children())).toList());
    }

    /**
     * Источник сигнатур, который знает только переданные подпрограммы. Имя сравнивается без
     * учёта регистра; пакет тоже, а {@code null} совпадает только с {@code null}
     * (автономная подпрограмма). Схема не учитывается. Неизвестное имя даёт пустой список,
     * как отсутствующая в базе подпрограмма.
     *
     * @param known подпрограммы, в том числе несколько перегрузок одного имени
     * @return источник сигнатур для {@code PlsqlApiFactory} или планировщика
     */
    public static SignatureSource source(SubprogramInfo... known) {
        return (schema, pkg, name) -> Arrays.stream(known)
                .filter(s -> s.name().equalsIgnoreCase(name))
                .filter(s -> pkg == null ? s.packageName() == null : pkg.equalsIgnoreCase(s.packageName()))
                .toList();
    }
}
