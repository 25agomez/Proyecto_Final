package cr.logisticaportuaria.optimizer.domain;

import cr.logisticaportuaria.yard.domain.ContainerType;
import java.time.Instant;
import java.util.UUID;

/**
 * Container handled by the stacking engine: either one already in the yard (with
 * {@link #slotId()}) or one that is arriving (without slot).
 *
 * @param code                BIC code
 * @param sizeFt              size in feet (20 or 40)
 * @param type                cargo type
 * @param weightKg            gross weight in kilograms (may be null)
 * @param etd                 estimated time of departure (may be null)
 * @param etdUncertaintyHours uncertainty of the ETD in hours (may be null)
 * @param clientCode          code of the client (may be null)
 * @param requiresPower       whether the container needs a reefer plug
 * @param imoClass            IMO class for hazardous cargo (may be null)
 * @param slotId              slot where it is stacked, or null when it is incoming
 */
public record ContainerUnit(
        String code,
        int sizeFt,
        ContainerType type,
        Double weightKg,
        Instant etd,
        Double etdUncertaintyHours,
        String clientCode,
        boolean requiresPower,
        String imoClass,
        UUID slotId) {

    /**
     * Validates the container.
     */
    public ContainerUnit {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("El contenedor necesita código.");
        }
        if (sizeFt != 20 && sizeFt != 40) {
            throw new IllegalArgumentException("El contenedor debe ser de 20 o 40 pies.");
        }
        if (type == null) {
            throw new IllegalArgumentException("El contenedor necesita tipo de carga.");
        }
        if (weightKg != null && weightKg < 0) {
            throw new IllegalArgumentException("El peso no puede ser negativo.");
        }
        // The IMO class is optional: the contract of the recommendations endpoint does not
        // carry it and the segregation is already guaranteed by the hazmat slot type.
    }

    /**
     * Builds an incoming container (not stacked yet).
     *
     * @return the container
     */
    public static ContainerUnit incoming(String code, int sizeFt, ContainerType type, Double weightKg, Instant etd,
            Double etdUncertaintyHours, String clientCode, boolean requiresPower, String imoClass) {
        return new ContainerUnit(code, sizeFt, type, weightKg, etd, etdUncertaintyHours, clientCode, requiresPower,
                imoClass, null);
    }

    /**
     * @param slot slot where the container is stacked
     * @return a copy of the container pointing to that slot
     */
    public ContainerUnit at(UUID slot) {
        return new ContainerUnit(code, sizeFt, type, weightKg, etd, etdUncertaintyHours, clientCode, requiresPower,
                imoClass, slot);
    }

    /**
     * @return true when the container needs a plug, either by type or by explicit request
     */
    public boolean needsPower() {
        return requiresPower || type == ContainerType.reefer;
    }

    /**
     * @return the weight, or 0 when it is unknown
     */
    public double weightOrZero() {
        return weightKg == null ? 0.0 : weightKg;
    }

    /**
     * @return the ETD as epoch milliseconds, or {@link Long#MAX_VALUE} when it is unknown
     */
    public long etdMillisOrFarFuture() {
        return etd == null ? Long.MAX_VALUE : etd.toEpochMilli();
    }
}
