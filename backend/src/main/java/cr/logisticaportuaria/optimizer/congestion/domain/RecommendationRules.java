package cr.logisticaportuaria.optimizer.congestion.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Actionable recommendations derived from a forecast.
 *
 * <p>Simple deterministic rules, written in Spanish for the supervisor: they never claim
 * more than the forecast supports.
 */
public final class RecommendationRules {

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private RecommendationRules() {
    }

    /**
     * Builds the recommendations.
     *
     * @param risk            risk level of the forecast
     * @param lanes           current lanes of the gate
     * @param utilization     offered load divided by the capacity
     * @param appointments    appointments in the next hour
     * @param target          instant of the horizon
     * @param p90             upper bound of the wait
     * @param holiday         whether the day is a holiday
     * @return at least one recommendation
     */
    public static List<String> suggest(RiskLevel risk, int lanes, double utilization, int appointments,
            Instant target, double p90, boolean holiday) {
        List<String> out = new ArrayList<>();
        String time = HOUR_MINUTE.format(target.atZone(CostaRicaCalendar.ZONE));
        if (risk == RiskLevel.ALTO) {
            out.add("Abrir al menos un carril adicional en la puerta antes de las " + time
                    + " (utilización prevista " + Math.round(utilization * 100) + " %).");
            if (appointments > 0) {
                out.add("Escalonar las citas de la próxima hora y avisar a los transportistas con espera > "
                        + Math.round(p90) + " min.");
            }
        } else if (risk == RiskLevel.MEDIO) {
            out.add("Reforzar la puerta antes de las " + time
                    + ": pre-validar documentos y habilitar el peso en una báscula secundaria.");
            if (utilization > 0.85) {
                out.add("Vigilar la fila: la utilización prevista (" + Math.round(utilization * 100)
                        + " %) deja poco margen para picos.");
            }
        } else {
            out.add("Operación normal: mantener los " + Math.max(1, lanes) + " carriles actuales sin cambios.");
            if (utilization > 1.0) {
                out.add("La demanda prevista (" + Math.round(utilization * 100)
                        + " % de la capacidad) supera la atención disponible: vigilar la fila"
                        + " y tener un carril de refuerzo listo.");
            }
        }
        if (holiday) {
            out.add("Feriado nacional: mantener el refuerzo mínimo y revisar la demanda real al mediodía.");
        }
        if (p90 > 30.0 && risk != RiskLevel.ALTO) {
            out.add("La espera podría superar los 30 min en el 10 % de los casos: preparar un carril de reserva.");
        }
        return out;
    }

    /**
     * Whether the instant falls in one of the two rush windows of the synthetic profile.
     *
     * @param instant any instant
     * @return true for 07:00-10:00 and 15:00-18:00 local time
     */
    public static boolean isRushHour(Instant instant) {
        ZonedDateTime local = instant.atZone(CostaRicaCalendar.ZONE);
        int hour = local.getHour();
        return (hour >= 7 && hour < 10) || (hour >= 15 && hour < 18);
    }

    /**
     * Name of the rush window of an instant.
     *
     * @param instant any instant
     * @return a Spanish label, or an empty string when it is not a rush window
     */
    public static String rushWindow(Instant instant) {
        ZonedDateTime local = instant.atZone(CostaRicaCalendar.ZONE);
        int hour = local.getHour();
        if (hour >= 7 && hour < 10) {
            return "punta de la mañana (07:00-10:00)";
        }
        if (hour >= 15 && hour < 18) {
            return "punta de la tarde (15:00-18:00)";
        }
        return "";
    }

    /**
     * Formats a duration in minutes as {@code hh:mm} for the recommendations.
     *
     * @param minutes minutes
     * @return the formatted duration
     */
    public static String duration(double minutes) {
        Duration duration = Duration.ofSeconds(Math.round(minutes * 60));
        return String.format("%d h %02d min", duration.toHours(), duration.toMinutesPart());
    }
}
