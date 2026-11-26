package cr.logisticaportuaria.optimizer.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import cr.logisticaportuaria.yard.domain.ContainerType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Request of {@code POST /api/v1/optimizer/recommendations} ({@code OptimizerRequest}).
 *
 * @param containerCode        BIC code of the incoming container (optional)
 * @param sizeFt               size in feet (20 or 40)
 * @param containerType        cargo type
 * @param weightKg             gross weight in kilograms (optional)
 * @param etd                  estimated time of departure (optional)
 * @param etdUncertaintyHours  uncertainty of the ETD in hours (optional)
 * @param clientCode           client that owns the container (optional)
 * @param requiresPower        whether the container needs a reefer plug (optional)
 */
public record OptimizerRequest(
        String containerCode,
        @NotNull Integer sizeFt,
        @NotNull ContainerType containerType,
        @DecimalMin("0.0") Double weightKg,
        Instant etd,
        @DecimalMin("0.0") Double etdUncertaintyHours,
        String clientCode,
        Boolean requiresPower) {

    /**
     * @return whether the size is one of the two accepted values
     */
    @AssertTrue(message = "El tamaño debe ser 20 o 40 pies.")
    @JsonIgnore
    public boolean isSizeValid() {
        return sizeFt != null && (sizeFt == 20 || sizeFt == 40);
    }
}
