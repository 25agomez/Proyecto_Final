package cr.logisticaportuaria.shared.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail. Every business action of the gate module goes
 * through this service, and the JPA listener registers the automatic changes of
 * the master data.
 */
@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Constructor.
     *
     * @param repository   repository of the audit trail
     * @param objectMapper mapper used to serialize the payloads
     */
    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an action in the audit trail.
     *
     * @param actor      who performed the action
     * @param action     what was done
     * @param entityType type of the affected entity
     * @param entityId   identifier of the affected entity
     * @param yardId     yard of the operation (may be null)
     * @param payload    details of the action
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String action, String entityType, Object entityId, UUID yardId,
            Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : new LinkedHashMap<>(payload);
        repository.save(new AuditLogEntry(
                yardId,
                actor,
                action,
                entityType,
                entityId == null ? null : entityId.toString(),
                safePayload));
    }

    /**
     * Records an action whose payload is the state of an entity.
     *
     * @param actor      who performed the action
     * @param action     what was done
     * @param entityType type of the affected entity
     * @param entityId   identifier of the affected entity
     * @param yardId     yard of the operation (may be null)
     * @param entity     the entity whose state is stored
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordState(String actor, String action, String entityType, Object entityId, UUID yardId,
            Object entity) {
        record(actor, action, entityType, entityId, yardId, describe(entity));
    }

    /**
     * Serializes an entity to a map that can be stored in the JSONB column.
     *
     * @param entity the entity
     * @return the map with the state of the entity
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> describe(Object entity) {
        if (entity == null) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(entity, Map.class);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("No se pudo serializar la entidad para la auditoría: {}", exception.getMessage());
            return Map.of("descripcion", entity.getClass().getSimpleName());
        }
    }
}
