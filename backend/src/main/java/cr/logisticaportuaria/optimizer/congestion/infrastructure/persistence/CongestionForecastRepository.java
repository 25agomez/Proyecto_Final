package cr.logisticaportuaria.optimizer.congestion.infrastructure.persistence;

import cr.logisticaportuaria.optimizer.congestion.domain.CongestionForecast;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Repository of the persisted congestion forecasts. */
public interface CongestionForecastRepository extends JpaRepository<CongestionForecast, UUID> {

    /**
     * Latest forecast of a gate and horizon.
     *
     * @param gateId     gate
     * @param horizonMin horizon in minutes
     * @return the most recent row, when it exists
     */
    Optional<CongestionForecast> findFirstByGateIdAndHorizonMinOrderByComputedAtDesc(UUID gateId, int horizonMin);

    /**
     * Forecasts of a gate computed after an instant, newest first.
     *
     * @param gateId gate
     * @param after  inclusive lower bound of {@code computed_at}
     * @return the rows
     */
    List<CongestionForecast> findByGateIdAndComputedAtAfterOrderByComputedAtDesc(UUID gateId, Instant after);

    /**
     * Deletes the rows older than the retention window.
     *
     * @param before exclusive upper bound of {@code computed_at}
     * @return the number of deleted rows
     */
    @Modifying
    @Transactional
    @Query("delete from CongestionForecast forecast where forecast.computedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
