package cr.logisticaportuaria.optimizer.congestion.domain;

/**
 * Confidence of a congestion forecast.
 *
 * <p>The names match the CHECK constraint of {@code congestion_forecast.confidence}
 * ({@code BAJA | MEDIA | ALTA}); the prompt calls the lowest level {@code LOW}, but the
 * database contract wins and the API exposes {@code BAJA}.
 */
public enum ConfidenceLevel {

    /** Not enough history: the forecast comes from the queueing model only. */
    BAJA,

    /** Some history: the hybrid model is used but the hold-out error is high. */
    MEDIA,

    /** Enough history with a low hold-out error: the hybrid model is used. */
    ALTA
}
