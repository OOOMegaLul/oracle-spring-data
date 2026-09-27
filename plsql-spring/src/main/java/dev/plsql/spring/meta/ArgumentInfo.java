package dev.plsql.spring.meta;

import java.util.ArrayList;
import java.util.List;

/**
 * Описывает один аргумент подпрограммы: одну строку словаря {@code ALL_ARGUMENTS} вместе с
 * вложенными в неё строками (поля записи, тип элемента коллекции).
 *
 * <p>{@code ALL_ARGUMENTS} — представление словаря данных Oracle (системного каталога, где
 * база хранит описания своих объектов) с одной строкой на каждый аргумент процедуры или
 * функции. Составные типы раскладываются там на несколько строк: сначала сам аргумент, затем
 * его части с большим уровнем вложенности ({@code DATA_LEVEL}). Здесь эти части собраны в
 * дерево {@code children}. Поля объектных типов SQL и элементы коллекций, которых нет в
 * {@code ALL_ARGUMENTS}, {@link DictionaryReader} дочитывает из {@code ALL_TYPE_ATTRS} и
 * {@code ALL_COLL_TYPES}.
 *
 * @param name        имя аргумента ({@code ARGUMENT_NAME}); {@code null} у возвращаемого значения
 *                    функции и у элемента коллекции
 * @param position    0 у возвращаемого значения функции, 1..n у аргументов в порядке объявления;
 *                    у вложенных строк — номер среди соседей того же уровня; у полей, дочитанных
 *                    из {@code ALL_TYPE_ATTRS} и {@code ALL_COLL_TYPES}, всегда 0
 * @param dataLevel   уровень вложенности ({@code DATA_LEVEL}): 0 у самого аргумента, 1 и больше
 *                    у полей записи и элементов коллекции
 * @param dataType    {@code ALL_ARGUMENTS.DATA_TYPE}, например {@code NUMBER},
 *                    {@code PL/SQL BOOLEAN}, {@code REF CURSOR}
 * @param plsType     {@code ALL_ARGUMENTS.PLS_TYPE}: тип так, как его видит PL/SQL (например,
 *                    {@code PLS_INTEGER}); может быть {@code null}
 * @param inOut       направление передачи: {@code IN}, {@code OUT} или {@code IN/OUT}
 * @param defaulted   {@code true}, если у аргумента есть значение по умолчанию ({@code DEFAULT})
 *                    и его можно не передавать
 * @param typeOwner   схема, которой принадлежит тип аргумента ({@code TYPE_OWNER}); {@code null}
 *                    у встроенных типов и у {@code %ROWTYPE}, восстановленного из исходного
 *                    текста (там схема уже входит в {@code typeName})
 * @param typeName    имя типа ({@code TYPE_NAME}); у типа, объявленного в пакете, — имя пакета;
 *                    у {@code %ROWTYPE} — таблица: Oracle 11.2 её в словаре не сообщает, и
 *                    {@link DictionaryReader} восстанавливает полное имя {@code OWNER.TABLE} из
 *                    исходного текста
 * @param typeSubname имя типа внутри пакета ({@code TYPE_SUBNAME}); заполнено только у типов,
 *                    объявленных в спецификации пакета
 * @param children    вложенные строки: поля записи или объектного типа, элемент коллекции;
 *                    пустой изменяемый список, если их нет
 * @param charLength  объявленная длина строкового типа в символах ({@code CHAR_LENGTH}),
 *                    например 100 у {@code VARCHAR2(100)}; {@code null}, если словарь её не
 *                    сообщает (тогда под элемент выходной index-by таблицы берётся 4000)
 */
public record ArgumentInfo(
        String name,
        int position,
        int dataLevel,
        String dataType,
        String plsType,
        String inOut,
        boolean defaulted,
        String typeOwner,
        String typeName,
        String typeSubname,
        List<ArgumentInfo> children,
        Integer charLength) {

    /**
     * Создаёт описание аргумента и заменяет {@code children}, равный {@code null}, на новый
     * пустой {@link ArrayList}.
     *
     * <p>Список должен быть изменяемым: при разборе строк словаря вложенные строки
     * добавляются в {@code children} уже после создания родителя.
     *
     * @param name        имя аргумента; {@code null} у возвращаемого значения и элемента коллекции
     * @param position    позиция в списке аргументов, 0 у возвращаемого значения
     * @param dataLevel   уровень вложенности, 0 у самого аргумента
     * @param dataType    тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @param plsType     тип из {@code ALL_ARGUMENTS.PLS_TYPE}, может быть {@code null}
     * @param inOut       {@code IN}, {@code OUT} или {@code IN/OUT}
     * @param defaulted   есть ли у аргумента значение по умолчанию
     * @param typeOwner   схема типа или {@code null}
     * @param typeName    имя типа (или пакета, где он объявлен) либо {@code null}
     * @param typeSubname имя типа внутри пакета или {@code null}
     * @param children    вложенные строки; {@code null} означает «нет вложенных»
     * @param charLength  объявленная длина в символах или {@code null}
     */
    public ArgumentInfo {
        children = children == null ? new ArrayList<>() : children;
    }

    /**
     * Создаёт описание аргумента без объявленной длины: для строк, у которых словарь её не
     * сообщает, и для описаний, собранных вручную (в тестах).
     *
     * @param name        имя аргумента; {@code null} у возвращаемого значения и элемента коллекции
     * @param position    позиция в списке аргументов, 0 у возвращаемого значения
     * @param dataLevel   уровень вложенности, 0 у самого аргумента
     * @param dataType    тип из {@code ALL_ARGUMENTS.DATA_TYPE}
     * @param plsType     тип из {@code ALL_ARGUMENTS.PLS_TYPE}, может быть {@code null}
     * @param inOut       {@code IN}, {@code OUT} или {@code IN/OUT}
     * @param defaulted   есть ли у аргумента значение по умолчанию
     * @param typeOwner   схема типа или {@code null}
     * @param typeName    имя типа (или пакета, где он объявлен) либо {@code null}
     * @param typeSubname имя типа внутри пакета или {@code null}
     * @param children    вложенные строки; {@code null} означает «нет вложенных»
     */
    public ArgumentInfo(String name, int position, int dataLevel, String dataType, String plsType, String inOut,
                        boolean defaulted, String typeOwner, String typeName, String typeSubname,
                        List<ArgumentInfo> children) {
        this(name, position, dataLevel, dataType, plsType, inOut, defaulted, typeOwner, typeName, typeSubname,
                children, null);
    }

    /**
     * Проверяет, передаётся ли значение в подпрограмму.
     *
     * @return {@code true} для {@code IN} и {@code IN/OUT}
     */
    public boolean isIn() {
        return inOut.startsWith("IN");
    }

    /**
     * Проверяет, возвращает ли подпрограмма значение через этот аргумент.
     *
     * @return {@code true} для {@code OUT} и {@code IN/OUT}
     */
    public boolean isOut() {
        return inOut.endsWith("OUT");
    }

    /**
     * Возвращает вид аргумента, то есть способ его передачи между Java и PL/SQL.
     *
     * @return результат {@link ArgKind#of(ArgumentInfo)} для этого аргумента
     */
    public ArgKind kind() {
        return ArgKind.of(this);
    }

    /**
     * Возвращает полное имя типа для объявления переменной в секции {@code DECLARE}
     * анонимного блока.
     *
     * <p>Для типа из пакета это {@code OWNER.PKG.REC_T}, для объектного типа —
     * {@code OWNER.TYPE_T}. Для {@code %ROWTYPE} возвращается имя таблицы
     * {@code OWNER.TABLE} без суффикса: {@code %ROWTYPE} к нему дописывает планировщик
     * вызова ({@code CallPlanner}).
     *
     * @return полное имя типа или {@code null}, если имя типа неизвестно
     */
    public String declaredType() {
        if (typeSubname != null) {
            return typeOwner + "." + typeName + "." + typeSubname;
        }
        if (typeName != null) {
            return (typeOwner != null ? typeOwner + "." : "") + typeName;
        }
        return null;
    }

    /**
     * Возвращает полное имя типа SQL для {@code createStruct} и {@code createOracleArray}.
     *
     * <p>Эти методы драйвера Oracle создают объект ({@code java.sql.Struct}) или коллекцию
     * ({@code java.sql.Array}) по имени типа в базе, например {@code APP.EMP_T}.
     *
     * @return {@code OWNER.TYPE_NAME} или только {@code TYPE_NAME}, если владелец не известен
     */
    public String sqlTypeName() {
        return (typeOwner != null ? typeOwner + "." : "") + typeName;
    }

    /**
     * Возвращает краткое описание аргумента для сообщений и логов, например
     * {@code NTENANT IN NUMBER DEFAULT}.
     *
     * @return имя (или {@code <return>} у возвращаемого значения), направление, тип
     *         и признак {@code DEFAULT}
     */
    @Override
    public String toString() {
        return (name == null ? "<return>" : name) + " " + inOut + " " + dataType
                + (typeName != null ? " (" + declaredType() + ")" : "")
                + (defaulted ? " DEFAULT" : "");
    }
}
