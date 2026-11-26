package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Truck;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository of the trucks ({@code truck}).
 */
public interface TruckRepository extends JpaRepository<Truck, UUID> {

    /**
     * @param plate normalized plate
     * @return the truck, when it exists
     */
    Optional<Truck> findByPlate(String plate);

    /**
     * @param carrierId carrier of the trucks (may be null)
     * @param active    whether the trucks are active (null means both)
     * @param pageable  pagination
     * @return the trucks that match
     */
    @Query("""
            SELECT t FROM Truck t
            WHERE (cast(:carrierId as uuid) IS NULL OR t.carrierId = :carrierId)
              AND (cast(:active as boolean) IS NULL OR t.active = :active)
            """)
    Page<Truck> search(@Param("carrierId") UUID carrierId,
            @Param("active") Boolean active,
            Pageable pageable);
}
