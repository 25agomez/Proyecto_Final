package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Driver;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository of the drivers ({@code driver}).
 */
public interface DriverRepository extends JpaRepository<Driver, UUID> {

    /**
     * @param namePart part of the name of the driver
     * @param pageable pagination
     * @return the drivers that match
     */
    Page<Driver> findByFullNameContainingIgnoreCase(String namePart, Pageable pageable);
}
