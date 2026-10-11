package dev.plsql.spring.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import dev.plsql.spring.meta.ArgumentInfo;

/**
 * Проверяет перевод значений в простые таблицы и обратно ({@link TableSplit}) без базы.
 */
class TableSplitTest {

    /** Формат, в котором {@code TIMESTAMP WITH TIME ZONE} едет строкой. */
    private static final DateTimeFormatter TSTZ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS xxx");

    /**
     * Проверяет, что момент времени для {@code TIMESTAMP WITH TIME ZONE} не сдвигается в час
     * перевода часов: осенью 01:30 по Нью-Йорку бывает дважды (05:30 и 06:30 UTC), и путь через
     * местное время JVM выбрал бы первое.
     */
    @Test
    void zonedValuesKeepTheInstantWhenClocksGoBack() {
        ArgumentInfo zoned = new ArgumentInfo("Z", 1, 1, "TIMESTAMP WITH TIME ZONE", null, "IN", false, null, null,
                null, List.of(), null);
        TimeZone saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        try {
            Instant second = Instant.parse("2024-11-03T06:30:00.000000001Z");
            assertThat(OffsetDateTime.parse((String) TableSplit.toTransport(second, zoned), TSTZ).toInstant())
                    .isEqualTo(second);
            assertThat(OffsetDateTime.parse((String) TableSplit.toTransport(Timestamp.from(second), zoned), TSTZ)
                    .toInstant()).isEqualTo(second);
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    /**
     * Проверяет поле, которое едет склеенным LOB: на входе — длины (у {@code null} — {@code null})
     * и склеенный текст или байты, на выходе — разрезание обратно по длинам, где пустое значение
     * не путается с {@code null}.
     */
    @Test
    void lobFieldsAreGluedAndCutBack() {
        ArgumentInfo doc = new ArgumentInfo("DOC", 1, 2, "CLOB", null, "IN/OUT", false, null, null, null, List.of(),
                null);
        ArgumentInfo data = new ArgumentInfo("DATA", 2, 2, "BLOB", null, "IN/OUT", false, null, null, null, List.of(),
                null);
        ArgumentInfo element = new ArgumentInfo(null, 1, 1, "PL/SQL RECORD", null, "IN/OUT", false, null, null, null,
                List.of(doc, data), null);
        ArgumentInfo table = new ArgumentInfo("T", 1, 0, "PL/SQL TABLE", null, "IN/OUT", false, "APP", "PKG", "T_TAB",
                List.of(element), null);
        List<TableSplit.Leaf> leaves = TableSplit.leaves(table);
        assertThat(leaves).allMatch(TableSplit::lob);

        List<java.util.Map<String, Object>> rows = List.of(java.util.Map.of("doc", "ab", "data", new byte[]{1}),
                java.util.Map.of("doc", "", "data", new byte[]{2, 3}), new java.util.HashMap<>());
        assertThat(TableSplit.lengths(rows, leaves.get(0))).containsExactly(2, 0, null);
        TableSplit.Glued glued = (TableSplit.Glued) TableSplit.content(rows, leaves.get(0), "T");
        assertThat(glued.text()).isEqualTo("ab");
        assertThat(glued.parts()).containsExactly("ab", "", null);
        assertThat(glued.name(1)).isEqualTo("T[2].DOC");
        assertThat(TableSplit.lengths(rows, leaves.get(1))).containsExactly(1, 2, null);
        assertThat((byte[]) TableSplit.content(rows, leaves.get(1), "T")).containsExactly(1, 2, 3);

        java.util.Map<String, Object> outs = new java.util.HashMap<>();
        outs.put("T[].DOC" + TableSplit.LENGTHS, java.util.Arrays.asList(new java.math.BigDecimal(2), java.math.BigDecimal.ZERO, null));
        outs.put("T[].DOC" + TableSplit.CONTENT, "ab");
        outs.put("T[].DATA" + TableSplit.LENGTHS, java.util.Arrays.asList(new java.math.BigDecimal(1), new java.math.BigDecimal(2), null));
        outs.put("T[].DATA" + TableSplit.CONTENT, new byte[]{1, 2, 3});
        TableSplit.fold(outs, List.of("T"));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> back = (List<java.util.Map<String, Object>>) outs.get("T");
        assertThat(back).hasSize(3);
        assertThat(back.get(0)).containsEntry("DOC", "ab");
        assertThat((byte[]) back.get(1).get("DATA")).containsExactly(2, 3);
        assertThat(back.get(1)).containsEntry("DOC", "");
        assertThat(back.get(2)).containsEntry("DOC", null).containsEntry("DATA", null);
    }

}
