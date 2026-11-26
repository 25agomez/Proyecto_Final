package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Appointment;
import cr.logisticaportuaria.yard.domain.AppointmentStatus;
import cr.logisticaportuaria.yard.domain.AppointmentType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository of the appointments ({@code appointment}).
 */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * @param yardId  yard of the appointments
     * @param status  status of the appointments (may be null)
     * @param type    type of the appointments (may be null)
     * @param from    lower bound of the schedule (may be null)
     * @param to      upper bound of the schedule (may be null)
     * @param pageable pagination
     * @return the matching appointments
     */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.yardId = :yardId
              AND (cast(:status as string) IS NULL OR a.status = :status)
              AND (cast(:type as string) IS NULL OR a.appointmentType = :type)
              AND (cast(:from as instant) IS NULL OR a.scheduledAt >= :from)
              AND (cast(:to as instant) IS NULL OR a.scheduledAt <= :to)
            """)
    Page<Appointment> search(@Param("yardId") UUID yardId,
            @Param("status") AppointmentStatus status,
            @Param("type") AppointmentType type,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * Appointments of a truck in a period: the candidate ones for a reading.
     *
     * @param truckId truck of the appointment
     * @param from    lower bound of the period
     * @param to      upper bound of the period
     * @return the appointments of the truck
     */
    List<Appointment> findByTruckIdAndScheduledAtBetween(UUID truckId, Instant from, Instant to);

    /**
     * @param yardId yard of the appointments
     * @param status status of the appointments
     * @return the appointments with the status
     */
    List<Appointment> findByYardIdAndStatus(UUID yardId, AppointmentStatus status);

    /**
     * @param containerCode container expected by the appointment
     * @return the appointments that expect the container
     */
    List<Appointment> findByContainerCodeIgnoreCase(String containerCode);
}
