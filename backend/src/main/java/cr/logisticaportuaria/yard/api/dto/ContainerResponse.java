package cr.logisticaportuaria.yard.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Container of the API.
 *
 * @param id             identifier of the container
 * @param code           BIC code (ISO 6346)
 * @param sizeFt         size in feet (20 or 40)
 * @param containerType  type of container (dry, reefer, hazmat)
 * @param status         status of the container
 * @param weightKg       weight in kilograms
 * @param clientCode     client that owns the container
 * @param eta            estimated arrival
 * @param etd            estimated departure
 * @param requiresPower  whether the container needs a plugged outlet
 * @param slot           slot where the container is placed (may be null)
 */
public record ContainerResponse(
        UUID id,
        String code,
        int sizeFt,
        String containerType,
        String status,
        BigDecimal weightKg,
        String clientCode,
        Instant eta,
        Instant etd,
        boolean requiresPower,
        SlotResponse slot) {
}
