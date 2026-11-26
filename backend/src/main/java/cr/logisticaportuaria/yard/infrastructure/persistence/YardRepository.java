package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.Yard;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository of the yards ({@code yard}).
 */
@Repository
public interface YardRepository extends JpaRepository<Yard, UUID> {

    /**
     * @param code code of the yard
     * @return the yard, when it exists
     */
    Optional<Yard> findByCode(String code);
}
