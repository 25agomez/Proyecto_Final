package cr.logisticaportuaria.yard.infrastructure.persistence;

import cr.logisticaportuaria.yard.domain.YardSlot;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository of the slots ({@code yard_slot}).
 */
@Repository
public interface YardSlotRepository extends JpaRepository<YardSlot, UUID> {

    /**
     * Slots of a yard with their occupancy.
     *
     * @param yardId    yard of the slots (may be null)
     * @param blockCode code of the block (may be null)
     * @param onlyFree  whether only the free slots are returned
     * @return the slots
     */
    @Query("""
            SELECT s.id AS id,
                   b.code AS blockCode,
                   s.bay AS bay,
                   s.row AS row,
                   s.tier AS tier,
                   s.sizeFt AS sizeFt,
                   s.slotType AS slotType,
                   s.hasPower AS hasPower,
                   CASE WHEN cp.id IS NULL THEN false ELSE true END AS occupied,
                   c.code AS containerCode
            FROM YardSlot s
            JOIN YardBlock b ON b.id = s.blockId
            LEFT JOIN ContainerPosition cp ON cp.slotId = s.id AND cp.active = true
            LEFT JOIN Container c ON c.id = cp.containerId
            WHERE (cast(:yardId as uuid) IS NULL OR b.yardId = :yardId)
              AND (cast(:blockCode as string) IS NULL OR b.code = :blockCode)
              AND (:onlyFree = false OR cp.id IS NULL)
            ORDER BY b.code, s.row, s.tier, s.bay
            """)
    List<SlotView> searchSlots(@Param("yardId") UUID yardId,
            @Param("blockCode") String blockCode,
            @Param("onlyFree") boolean onlyFree);

    /**
     * Slots of a yard with the given filters.
     *
     * @param yardId   yard of the slots (may be null)
     * @param blockCode code of the block (may be null)
     * @param row      row of the slot (may be null)
     * @param tier     tier of the slot (may be null)
     * @param pageable pagination
     * @return the matching slots
     */
    @Query("""
            SELECT s FROM YardSlot s
            JOIN YardBlock b ON b.id = s.blockId
            WHERE (cast(:yardId as uuid) IS NULL OR b.yardId = :yardId)
              AND (cast(:blockCode as string) IS NULL OR b.code = :blockCode)
              AND (cast(:row as integer) IS NULL OR s.row = :row)
              AND (cast(:tier as integer) IS NULL OR s.tier = :tier)
            """)
    Page<YardSlot> search(@Param("yardId") UUID yardId,
            @Param("blockCode") String blockCode,
            @Param("row") Integer row,
            @Param("tier") Integer tier,
            Pageable pageable);

    /**
     * @param blockId block of the slots
     * @return the slots of the block
     */
    java.util.List<YardSlot> findByBlockIdOrderByRowAscTierAscBay(UUID blockId);
}
