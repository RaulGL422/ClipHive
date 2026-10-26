package io.github.raulgl422.cliphive.server.publish;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What kind of day it is for an audience in Spain (DESIGN.md §10.4), so the
 * planner picks hours that fit: weekend or national holiday, the season, and
 * the special nights when nobody is on their phone. Pure — no I/O. Texts are
 * in Spanish because they go to Claude as-is.
 */
final class SpanishCalendar {

    private static final Map<MonthDay, String> FIXED_HOLIDAYS = Map.of(
            MonthDay.of(1, 1), "Año Nuevo",
            MonthDay.of(1, 6), "Reyes",
            MonthDay.of(5, 1), "Día del Trabajo",
            MonthDay.of(8, 15), "Asunción",
            MonthDay.of(10, 12), "Fiesta Nacional",
            MonthDay.of(11, 1), "Todos los Santos",
            MonthDay.of(12, 6), "Día de la Constitución",
            MonthDay.of(12, 8), "Inmaculada",
            MonthDay.of(12, 25), "Navidad");

    private static final Map<MonthDay, String> SPECIAL_DATES = Map.of(
            MonthDay.of(12, 24), "Nochebuena: de 21:00 a 00:00 casi todo el mundo está cenando en familia",
            MonthDay.of(12, 31), "Nochevieja: de 21:00 a 01:00 la gente está de cena y campanadas; mejor mañana y mediodía",
            MonthDay.of(1, 1), "Año Nuevo: la mañana es muy floja, la gente se levanta tarde",
            MonthDay.of(1, 5), "Víspera de Reyes: cabalgatas por la tarde (17:00-20:00)");

    private SpanishCalendar() {
    }

    /** The day as Claude gets it: {@code fin_de_semana}, {@code festivo}, {@code temporada}, {@code fecha_especial}. */
    static Map<String, Object> describe(LocalDate day) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fin_de_semana", day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY);
        String holiday = holiday(day);
        if (holiday != null) out.put("festivo_nacional", holiday);
        out.put("temporada", season(day));
        String special = SPECIAL_DATES.get(MonthDay.from(day));
        if (special != null) out.put("fecha_especial", special);
        return out;
    }

    /** National holiday name (fixed ones and Good Friday), or null. */
    static String holiday(LocalDate day) {
        String fixed = FIXED_HOLIDAYS.get(MonthDay.from(day));
        if (fixed != null) return fixed;
        return day.equals(easter(day.getYear()).minusDays(2)) ? "Viernes Santo" : null;
    }

    static String season(LocalDate day) {
        LocalDate easter = easter(day.getYear());
        int month = day.getMonthValue();
        int dom = day.getDayOfMonth();
        if ((month == 12 && dom >= 20) || (month == 1 && dom <= 7)) return "Navidad (vacaciones escolares)";
        if (!day.isBefore(easter.minusDays(7)) && !day.isAfter(easter.plusDays(1))) return "Semana Santa";
        if (month == 7 || month == 8) return "verano (vacaciones; las noches se alargan)";
        if (month == 9 && dom <= 20) return "vuelta al cole";
        return "normal";
    }

    /** Easter Sunday, Gregorian calendar (anonymous algorithm). */
    static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int dayOfMonth = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, dayOfMonth);
    }
}
