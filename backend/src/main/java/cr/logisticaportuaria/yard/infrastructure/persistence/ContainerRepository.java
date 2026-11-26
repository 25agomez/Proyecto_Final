package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository of the containers ({@code container}).
 */
public interface ContainerRepository extends JpaRepository<Container, UUID> {

    /**
     * @param code BIC code
     * @return the container, when it exists
     */
    Optional<Container> findByCode(String code);

    /**
     * Containers with the given filters.
     *
     * @param codePart   part of the BIC code (may be null)
     * @param status     status of the container (may be null)
     * @param clientCode client that owns the container (may be null)
     * @param from       lower bound of the ETD (may be null)
     * @param to         upper bound of the ETD (may be null)
     * @param pageable   pagination
     * @return the matching containers
     */
    @Query("""
            SELECT c FROM Container c
            WHERE (cast(:codePart as string) IS NULL
                   OR upper(c.code) LIKE upper(concat('%', cast(:codePart as string), '%')))
              AND (cast(:status as string) IS NULL OR c.status = :status)
              AND (cast(:clientCode as string) IS NULL OR c.clientCode = :clientCode)
              AND (cast(:from as instant) IS NULL OR c.etd >= :from)
              AND (cast(:to as instant) IS NULL OR c.etd <= :to)
            """)
    Page<Container> search(@Param("codePart") String codePart,
            @Param("status") ContainerStatus status,
            @Param("clientCode") String clientCode,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * @param status status of the containers
     * @return the containers with the status
     */
    List<Container> findByStatus(ContainerStatus status);
}
