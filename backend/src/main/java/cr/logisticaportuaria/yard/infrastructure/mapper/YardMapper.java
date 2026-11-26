package cr.logisticaportuaria.yard.infrastructure.mapper;

import cr.logisticaportuaria.yard.api.dto.ContainerResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.AppointmentResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.CarrierResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.DriverResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.GateResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.TruckResponse;
import cr.logisticaportuaria.yard.api.dto.SlotResponse;
import cr.logisticaportuaria.yard.domain.Appointment;
import cr.logisticaportuaria.yard.domain.Carrier;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.Driver;
import cr.logisticaportuaria.yard.domain.Gate;
import cr.logisticaportuaria.yard.domain.Truck;
import cr.logisticaportuaria.yard.infrastructure.persistence.SlotView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapping between the entities of the yard and their DTOs.
 */
@Mapper(componentModel = "spring")
public interface YardMapper {

    /**
     * @param container container
     * @return the DTO of the container (without the slot, which is filled by the service)
     */
    @Mapping(target = "slot", ignore = true)
    ContainerResponse toContainerResponse(Container container);

    /**
     * @param view slot with its occupancy
     * @return the DTO of the slot
     */
    @Mapping(target = "distanceToGateMeters", ignore = true)
    SlotResponse toSlotResponse(SlotView view);

    /**
     * @param carrier carrier
     * @return the DTO of the carrier
     */
    CarrierResponse toCarrierResponse(Carrier carrier);

    /**
     * @param driver driver
     * @return the DTO of the driver
     */
    DriverResponse toDriverResponse(Driver driver);

    /**
     * @param truck truck
     * @return the DTO of the truck
     */
    TruckResponse toTruckResponse(Truck truck);

    /**
     * @param appointment appointment
     * @return the DTO of the appointment
     */
    AppointmentResponse toAppointmentResponse(Appointment appointment);

    /**
     * @param gate gate
     * @return the DTO of the gate
     */
    GateResponse toGateResponse(Gate gate);
}
