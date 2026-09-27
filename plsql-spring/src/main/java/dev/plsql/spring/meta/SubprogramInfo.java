package dev.plsql.spring.meta;

import java.util.List;

/**
 * Описывает одну перегрузку процедуры или функции так, как её описывает словарь
 * {@code ALL_ARGUMENTS}.
 *
 * <p>Перегрузка — это несколько подпрограмм с одним именем в одном пакете, которые
 * отличаются списком аргументов. {@code ALL_ARGUMENTS} нумерует их в столбце
 * {@code OVERLOAD}; каждой перегрузке соответствует свой {@code SubprogramInfo}.
 *
 * @param owner       схема, где на самом деле лежит код (уже после разрешения синонимов)
 * @param packageName пакет или {@code null} у автономной процедуры или функции (объявленной
 *                    вне пакета, командой {@code CREATE PROCEDURE} или {@code CREATE FUNCTION})
 * @param name        имя процедуры или функции
 * @param overload    номер перегрузки из {@code ALL_ARGUMENTS.OVERLOAD} в виде строки;
 *                    {@code null}, если перегрузок нет
 * @param returnValue возвращаемое значение функции; {@code null} у процедуры
 * @param arguments   аргументы верхнего уровня в порядке объявления
 */
public record SubprogramInfo(
        String owner,
        String packageName,
        String name,
        String overload,
        ArgumentInfo returnValue,
        List<ArgumentInfo> arguments) {

    /**
     * Проверяет, является ли подпрограмма функцией, то есть возвращает ли она значение.
     *
     * @return {@code true}, если {@link #returnValue()} не {@code null}
     */
    public boolean isFunction() {
        return returnValue != null;
    }

    /**
     * Возвращает полное имя для вызова из PL/SQL: {@code OWNER.PKG.NAME} или
     * {@code OWNER.NAME} для автономной подпрограммы.
     *
     * @return полное имя подпрограммы
     */
    public String qualifiedName() {
        return owner + "." + (packageName != null ? packageName + "." : "") + name;
    }

    /**
     * Возвращает описание для сообщений и логов: полное имя, номер перегрузки после
     * {@code #}, список аргументов и тип результата функции после {@code RETURN}.
     *
     * @return описание подпрограммы, например {@code APP.PKG.GET_EMP#2[P_ID IN NUMBER] RETURN NUMBER}
     */
    @Override
    public String toString() {
        return qualifiedName() + (overload != null ? "#" + overload : "") + arguments
                + (returnValue != null ? " RETURN " + returnValue.dataType() : "");
    }
}
