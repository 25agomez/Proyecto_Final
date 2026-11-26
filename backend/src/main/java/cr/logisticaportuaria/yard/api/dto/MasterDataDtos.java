package cr.logisticaportuaria.yard.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTOs of the master data of the yard (containers, trucks, drivers,
 * carriers, appointments and gates).
 *
 * <p>They are grouped in one class to keep the package small: each one is a
 * record, as the rest of the API.
 */
public final class MasterDataDtos {

    private MasterDataDtos() {
        // container of records
    }

    /**
     * Transport company.
     *
     * @param id     identifier
     * @param code   short code
     * @param name   legal name
     * @param taxId  tax identification
     * @param active whether the carrier is active
     */
    public record CarrierResponse(
            UUID id,
            String code,
            String name,
            String taxId,
            boolean active) {
    }

    /**
     * Driver.
     *
     * @param id        identifier
     * @param fullName  name of the driver
     * @param licenseId license identification
     * @param phone     contact phone
     */
    public record DriverResponse(
            UUID id,
            String fullName,
            String licenseId,
            String phone) {
    }

    /**
     * Truck.
     *
     * @param id        identifier
     * @param plate     normalized plate
     * @param carrierId carrier of the truck
     * @param driverId  driver of the truck
     * @param active    whether the truck is active
     */
    public record TruckResponse(
            UUID id,
            String plate,
            UUID carrierId,
            UUID driverId,
            boolean active) {
    }

    /**
     * Appointment.
     *
     * @param id              identifier
     * @param yardId          yard of the appointment
     * @param gateId          gate assigned to the appointment
     * @param truckId         truck of the appointment
     * @param appointmentType INGRESO or RETIRO
     * @param scheduledAt     scheduled instant
     * @param windowMinutes   window of the appointment
     * @param status          status of the appointment
     * @param containerCode   container expected
     * @param notes           free notes
     */
    public record AppointmentResponse(
            UUID id,
            UUID yardId,
            UUID gateId,
            UUID truckId,
            String appointmentType,
            Instant scheduledAt,
            int windowMinutes,
            String status,
            String containerCode,
            String notes) {
    }

    /**
     * Gate.
     *
     * @param id     identifier
     * @param yardId yard of the gate
     * @param code   code of the gate
     * @param name   name of the gate
     * @param kind   direction accepted by the gate
     * @param lanes  number of lanes
     * @param active whether the gate is active
     */
    public record GateResponse(
            UUID id,
            UUID yardId,
            String code,
            String name,
            String kind,
            int lanes,
            boolean active) {
    }

    /**
     * Body to create or update a transport company.
     *
     * @param code  short code
     * @param name  legal name
     * @param taxId tax identification
     */
    public record CarrierRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 64) String taxId) {
    }

    /**
     * Body to create or update a driver.
     *
     * @param fullName  name of the driver
     * @param licenseId license identification
     * @param phone     contact phone
     */
    public record DriverRequest(
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 64) String licenseId,
            @Size(max = 32) String phone) {
    }

    /**
     * Body to create or update a truck.
     *
     * @param plate     normalized plate
     * @param carrierId carrier of the truck
     * @param driverId  driver of the truck
     */
    public record TruckRequest(
            @NotBlank @Size(max = 16) String plate,
            UUID carrierId,
            UUID driverId) {
    }

    /**
     * Body to create or update an appointment.
     *
     * @param yardId          yard of the appointment
     * @param gateId          gate assigned to the appointment
     * @param truckId         truck of the appointment
     * @param appointmentType INGRESO or RETIRO
     * @param scheduledAt     scheduled instant
     * @param windowMinutes   window of the appointment
     * @param containerCode   container expected
     * @param notes           free notes
     */
    public record AppointmentRequest(
            @NotNull UUID yardId,
            UUID gateId,
            UUID truckId,
            @NotBlank String appointmentType,
            @NotNull Instant scheduledAt,
            @Positive Integer windowMinutes,
            @Size(max = 16) String containerCode,
            String notes) {
    }

    /**
     * Body to create or update a gate.
     *
     * @param yardId yard of the gate
     * @param code   code of the gate
     * @param name   name of the gate
     * @param kind   direction accepted by the gate
     * @param lanes  number of lanes
     */
    public record GateRequest(
            @NotNull UUID yardId,
            @NotBlank @Size(max = 16) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String kind,
            @Positive Integer lanes) {
    }

    /**
     * Body to create or update a container.
     *
     * @param code           BIC code (ISO 6346)
     * @param sizeFt         size in feet (20 or 40)
     * @param containerType  type of container
     * @param status         status of the container
     * @param weightKg       weight in kilograms
     * @param clientCode     client that owns the container
     * @param eta            estimated arrival
     * @param etd            estimated departure
     * @param requiresPower  whether the container needs a plugged outlet
     */
    public record ContainerRequest(
            @NotBlank @Size(max = 16) String code,
            @Positive Integer sizeFt,
            @NotBlank String containerType,
            @NotBlank String status,
            BigDecimal weightKg,
            @Size(max = 32) String clientCode,
            Instant eta,
            Instant etd,
            Boolean requiresPower) {
    }
}
