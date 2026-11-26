package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Costa Rican calendar used by the congestion model.
 *
 * <p>The holidays are the national ones: the fixed dates plus Thursday and Friday of the
 * Holy Week, which are computed with the anonymous Gregorian algorithm. Local times always
 * use {@code America/Costa_Rica} (UTC-6, no daylight saving time).
 */
public final class CostaRicaCalendar {

    /** Time zone used by the UI and by the calendar of the model. */
    public static final ZoneId ZONE = ZoneId.of("America/Costa_Rica");

    private CostaRicaCalendar() {
    }

    /**
     * National holidays of a year.
     *
     * @param year the year
     * @return the holidays, in local time
     */
    public static Set<LocalDate> holidays(int year) {
        Set<LocalDate> days = new HashSet<>();
        days.add(LocalDate.of(year, 1, 1));   // Año Nuevo
        days.add(LocalDate.of(year, 4, 11));  // Juan Santamaría
        days.add(LocalDate.of(year, 5, 1));   // Día Internacional del Trabajo
        days.add(LocalDate.of(year, 7, 25));  // Anexión del Partido de Nicoya
        days.add(LocalDate.of(year, 8, 2));   // Virgen de los Ángeles
        days.add(LocalDate.of(year, 8, 15));  // Día de la Madre
        days.add(LocalDate.of(year, 9, 15));  // Independencia
        days.add(LocalDate.of(year, 12, 25)); // Navidad
        LocalDate easter = easterSunday(year);
        days.add(easter.minusDays(3));        // Jueves Santo
        days.add(easter.minusDays(2));        // Viernes Santo
        return Set.copyOf(days);
    }

    /**
     * Whether the local date is a holiday.
     *
     * @param date local date
     * @return true for a national holiday
     */
    public static boolean isHoliday(LocalDate date) {
        return holidays(date.getYear()).contains(date);
    }

    /**
     * Whether the local date of the instant is a holiday.
     *
     * @param instant any instant
     * @return true for a national holiday
     */
    public static boolean isHoliday(Instant instant) {
        return isHoliday(instant.atZone(ZONE).toLocalDate());
    }

    /**
     * Local date-time of an instant.
     *
     * @param instant any instant
     * @return the date-time in Costa Rica
     */
    public static ZonedDateTime local(Instant instant) {
        return instant.atZone(ZONE);
    }

    /**
     * Easter Sunday of a year (anonymous Gregorian algorithm).
     *
     * @param year the year
     * @return the date of Easter Sunday
     */
    public static LocalDate easterSunday(int year) {
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
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /**
     * ISO day of the week of a local date.
     *
     * @param date local date
     * @return 1 (Monday) to 7 (Sunday)
     */
    public static int isoDayOfWeek(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day.getValue();
    }

    /**
     * Number of days of a month, used by the heatmap validation.
     *
     * @param year  year
     * @param month month (1-12)
     * @return the number of days
     */
    public static int daysInMonth(int year, int month) {
        return YearMonth.of(year, month).lengthOfMonth();
    }
}
