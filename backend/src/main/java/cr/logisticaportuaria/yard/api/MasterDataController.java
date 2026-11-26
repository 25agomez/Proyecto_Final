package cr.logisticaportuaria.yard.api;

import cr.logisticaportuaria.shared.api.PageResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.AppointmentRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.AppointmentResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.CarrierRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.CarrierResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.DriverRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.DriverResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.GateRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.GateResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.TruckRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.TruckResponse;
import cr.logisticaportuaria.yard.application.MasterDataService;
import cr.logisticaportuaria.yard.domain.AppointmentStatus;
import cr.logisticaportuaria.yard.domain.AppointmentType;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API of the master data of the yard: transport companies, drivers, trucks,
 * appointments and gates.
 *
 * <p>These endpoints are an extension of the OpenAPI contract of P1, which
 * only described the containers and the slots (see the report of the contract
 * test).
 */
@RestController
@RequestMapping("/api/v1")
public class MasterDataController {

    private final MasterDataService masterDataService;

    /**
     * Constructor.
     *
     * @param masterDataService service of the master data
     */
    public MasterDataController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    /**
     * @param active   whether only the active carriers are returned (optional)
     * @param page     zero based page number
     * @param size     size of the page
     * @return the transport companies
     */
    @GetMapping("/carriers")
    public PageResponse<CarrierResponse> listCarriers(
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return masterDataService.listCarriers(active, pageOf(page, size));
    }

    /**
     * @param request body of the carrier
     * @return the created transport company
     */
    @PostMapping("/carriers")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<CarrierResponse> createCarrier(@Valid @RequestBody CarrierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createCarrier(request));
    }

    /**
     * @param id      identifier of the carrier
     * @param request body of the carrier
     * @return the updated transport company
     */
    @PutMapping("/carriers/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public CarrierResponse updateCarrier(@PathVariable UUID id, @Valid @RequestBody CarrierRequest request) {
        return masterDataService.updateCarrier(id, request);
    }

    /**
     * @param name part of the name of the driver (optional)
     * @param page zero based page number
     * @param size size of the page
     * @return the drivers
     */
    @GetMapping("/drivers")
    public PageResponse<DriverResponse> listDrivers(
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return masterDataService.listDrivers(name, pageOf(page, size));
    }

    /**
     * @param request body of the driver
     * @return the created driver
     */
    @PostMapping("/drivers")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<DriverResponse> createDriver(@Valid @RequestBody DriverRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createDriver(request));
    }

    /**
     * @param id      identifier of the driver
     * @param request body of the driver
     * @return the updated driver
     */
    @PutMapping("/drivers/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public DriverResponse updateDriver(@PathVariable UUID id, @Valid @RequestBody DriverRequest request) {
        return masterDataService.updateDriver(id, request);
    }

    /**
     * @param carrierId carrier of the trucks (optional)
     * @param active    whether only the active trucks are returned (optional)
     * @param page      zero based page number
     * @param size      size of the page
     * @return the trucks
     */
    @GetMapping("/trucks")
    public PageResponse<TruckResponse> listTrucks(
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return masterDataService.listTrucks(carrierId, active, pageOf(page, size));
    }

    /**
     * @param request body of the truck
     * @return the created truck
     */
    @PostMapping("/trucks")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<TruckResponse> createTruck(@Valid @RequestBody TruckRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createTruck(request));
    }

    /**
     * @param id      identifier of the truck
     * @param request body of the truck
     * @return the updated truck
     */
    @PutMapping("/trucks/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public TruckResponse updateTruck(@PathVariable UUID id, @Valid @RequestBody TruckRequest request) {
        return masterDataService.updateTruck(id, request);
    }

    /**
     * @param yardId   yard of the appointments
     * @param status   status of the appointments (optional)
     * @param type     type of the appointments (optional)
     * @param from     lower bound of the schedule (optional)
     * @param to       upper bound of the schedule (optional)
     * @param page     zero based page number
     * @param size     size of the page
     * @return the appointments
     */
    @GetMapping("/appointments")
    public PageResponse<AppointmentResponse> listAppointments(
            @RequestParam(required = false) UUID yardId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) AppointmentType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return masterDataService.listAppointments(yardId, status, type, from, to, pageOf(page, size));
    }

    /**
     * @param request body of the appointment
     * @return the created appointment
     */
    @PostMapping("/appointments")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<AppointmentResponse> createAppointment(@Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createAppointment(request));
    }

    /**
     * @param id      identifier of the appointment
     * @param request body of the appointment
     * @return the updated appointment
     */
    @PutMapping("/appointments/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public AppointmentResponse updateAppointment(@PathVariable UUID id,
            @Valid @RequestBody AppointmentRequest request) {
        return masterDataService.updateAppointment(id, request);
    }

    /**
     * @param yardId yard of the gates (optional)
     * @return the gates
     */
    @GetMapping("/gates")
    public List<GateResponse> listGates(@RequestParam(required = false) UUID yardId) {
        return masterDataService.listGates(yardId);
    }

    /**
     * @param request body of the gate
     * @return the created gate
     */
    @PostMapping("/gates")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public ResponseEntity<GateResponse> createGate(@Valid @RequestBody GateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(masterDataService.createGate(request));
    }

    /**
     * @param id      identifier of the gate
     * @param request body of the gate
     * @return the updated gate
     */
    @PutMapping("/gates/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")
    public GateResponse updateGate(@PathVariable UUID id, @Valid @RequestBody GateRequest request) {
        return masterDataService.updateGate(id, request);
    }

    private PageRequest pageOf(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 200));
    }
}
