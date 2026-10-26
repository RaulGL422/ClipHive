package io.github.raulgl422.cliphive.server.publish;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SpanishCalendarTest {

    @Test
    void easter_and_good_friday() {
        assertThat(SpanishCalendar.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(SpanishCalendar.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        assertThat(SpanishCalendar.holiday(LocalDate.of(2026, 4, 3))).isEqualTo("Viernes Santo");
        assertThat(SpanishCalendar.season(LocalDate.of(2026, 4, 1))).isEqualTo("Semana Santa");
    }

    @Test
    void a_day_is_described_for_the_planner() {
        var national = SpanishCalendar.describe(LocalDate.of(2026, 10, 12));
        assertThat(national).containsEntry("festivo_nacional", "Fiesta Nacional").containsEntry("fin_de_semana", false)
                .containsEntry("temporada", "normal");

        var newYearsEve = SpanishCalendar.describe(LocalDate.of(2026, 12, 31));
        assertThat(newYearsEve).doesNotContainKey("festivo_nacional").containsKey("fecha_especial");
        assertThat((String) newYearsEve.get("temporada")).startsWith("Navidad");

        assertThat(SpanishCalendar.describe(LocalDate.of(2026, 8, 1))).containsEntry("fin_de_semana", true);
        assertThat((String) SpanishCalendar.describe(LocalDate.of(2026, 8, 1)).get("temporada")).startsWith("verano");
    }
}
