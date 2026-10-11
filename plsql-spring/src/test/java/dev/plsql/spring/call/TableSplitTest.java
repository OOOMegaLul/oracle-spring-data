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
}
