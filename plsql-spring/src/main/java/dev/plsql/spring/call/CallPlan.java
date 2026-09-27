package dev.plsql.spring.call;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.core.ResolvableType;

import dev.plsql.spring.meta.ArgKind;
import dev.plsql.spring.meta.ArgumentInfo;
import dev.plsql.spring.meta.SubprogramInfo;

/**
 * Описывает всё, что нужно для вызова одного метода: сгенерированный блок и его привязки.
 * Строится один раз при старте и используется повторно при каждом вызове.
 *
 * <p>План создаёт {@link CallPlanner}, выполняет {@link CallExecutor}. Привязка (bind) — это
 * значение, которое JDBC подставляет на место знака {@code ?} в тексте SQL перед выполнением
 * или читает оттуда после выполнения (если это OUT-параметр, то есть выходной). Знаки
 * {@code ?} нумеруются по порядку появления в тексте, поэтому {@code binds} идут строго в
 * том же порядке.
 *
 * @param target     подпрограмма Oracle (процедура или функция, одна конкретная перегрузка),
 *                   которую вызывает план
 * @param sql        анонимный блок PL/SQL с позиционными плейсхолдерами {@code ?}; анонимный
 *                   блок — безымянный фрагмент {@code DECLARE ... BEGIN ... END;}, который
 *                   база выполняет как один оператор и нигде не хранит
 * @param binds      по одной записи на каждый плейсхолдер, в порядке их появления в тексте
 * @param recordOuts OUT-аргументы типа RECORD, чьи поля возвращаются отдельными привязками
 *                   ({@code ARG.FIELD}) и затем собираются обратно в одну карту
 * @param result     как выходные значения превращаются в результат метода
 */
public record CallPlan(
        SubprogramInfo target,
        String sql,
        List<Bind> binds,
        List<String> recordOuts,
        Result result) {

    /**
     * Описывает один плейсхолдер {@code ?}: что в него передать и что из него прочитать.
     *
     * <p>Один плейсхолдер может быть сразу входным и выходным (IN OUT-аргумент, который
     * привязывается напрямую): тогда заданы и {@code in}, и {@code outKey}.
     *
     * @param kind    как привязывать значение; для поля записи — вид самого поля. Может
     *                отличаться от вида аргумента: входной XMLTYPE привязывается как
     *                {@code CLOB}
     * @param arg     аргумент (или поле записи), который несёт этот плейсхолдер
     * @param in      откуда берётся входное значение: функция от массива аргументов вызова
     *                Java-метода; {@code null} для чисто выходного плейсхолдера
     * @param outKey  имя, под которым сохраняется выходное значение; {@code null} для чисто
     *                входного плейсхолдера. Для поля записи это {@code ARG.FIELD}, для
     *                результата функции — {@link CallPlanner#RETURN_KEY}
     * @param outType Java-тип, в который пойдёт выходное значение; по нему строки REF CURSOR
     *                (ссылки на открытый результат запроса) превращаются в объекты. Для полей
     *                записи {@code null}
     */
    public record Bind(
            ArgKind kind,
            ArgumentInfo arg,
            Function<Object[], Object> in,
            String outKey,
            ResolvableType outType) {
    }

    /**
     * Описывает, как из выходных значений вызова получить значение, которое вернёт Java-метод.
     *
     * @param returnType объявленный тип результата Java-метода, вместе с параметрами
     *                   дженериков
     * @param returnKey  ключ выходного значения, которое становится результатом метода, или
     *                   {@code null}
     * @param outsToType если {@code true}, все выходные значения вместе собираются в этот тип
     *                   (record, бин или {@code Map})
     */
    public record Result(ResolvableType returnType, String returnKey, boolean outsToType) {

        /**
         * Собирает результат Java-метода из выходных значений вызова.
         *
         * <p>Для {@code void} возвращает {@code null}. Если {@code outsToType}, вся карта
         * выходных значений превращается в {@code returnType}: компоненты record или свойства
         * бина заполняются по именам OUT-аргументов. Иначе в {@code returnType} превращается
         * одно значение, лежащее под {@code returnKey}. Преобразование выполняет
         * {@code Values.convert}: например, оборачивает значение в {@code Optional}. {@code NULL}
         * для примитивного типа — ошибка с именем выхода: {@code NULL} не превращается молча в
         * {@code 0} или {@code false}.
         *
         * @param outs выходные значения по ключам {@link CallPlan.Bind#outKey()}; поля
         *             записей к этому моменту уже собраны в карты
         * @return значение, которое вернёт Java-метод
         * @throws org.springframework.dao.EmptyResultDataAccessException если результат или его
         *         примитивный компонент {@code NULL}
         */
        public Object assemble(Map<String, Object> outs) {
            Class<?> raw = returnType.resolve(Object.class);
            if (raw == void.class || raw == Void.class) {
                return null;
            }
            if (outsToType) {
                return dev.plsql.spring.support.Values.convert(outs, returnType);
            }
            try {
                return dev.plsql.spring.support.Values.convert(outs.get(returnKey), returnType);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                String what = CallPlanner.RETURN_KEY.equals(returnKey) ? "the function" : "OUT argument " + returnKey;
                throw new org.springframework.dao.EmptyResultDataAccessException(what + " returned NULL: " + e.getMessage(), 1, e);
            }
        }
    }
}
