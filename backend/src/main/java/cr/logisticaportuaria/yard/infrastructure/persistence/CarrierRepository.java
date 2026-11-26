package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Carrier;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository of the transport companies ({@code carrier}).
 */
public interface CarrierRepository extends JpaRepository<Carrier, UUID> {

    /**
     * @param code code of the carrier
     * @return the carrier, when it exists
     */
    Optional<Carrier> findByCode(String code);

    /**
     * @param active  whether the carriers are active (null means both)
     * @param pageable pagination
     * @return the carriers
     */
    @Query("""
            SELECT c FROM Carrier c
            WHERE (cast(:active as boolean) IS NULL OR c.active = :active)
            """)
    Page<Carrier> search(@Param("active") Boolean active, Pageable pageable);
}
