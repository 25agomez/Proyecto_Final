package cr.logisticaportuaria.yard.application;

import cr.logisticaportuaria.shared.api.BusinessRuleException;
import cr.logisticaportuaria.shared.api.NotFoundException;
import cr.logisticaportuaria.shared.api.PageResponse;
import cr.logisticaportuaria.shared.audit.AuditService;
import cr.logisticaportuaria.shared.domain.Iso6346;
import cr.logisticaportuaria.shared.domain.TextNormalizer;
import cr.logisticaportuaria.shared.security.CurrentActor;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.AppointmentRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.AppointmentResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.CarrierRequest;
import cr.logisticaportuaria.yard.api.dto.ContainerResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.CarrierResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.ContainerRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.DriverRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.DriverResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.GateRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.GateResponse;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.TruckRequest;
import cr.logisticaportuaria.yard.api.dto.MasterDataDtos.TruckResponse;
import cr.logisticaportuaria.yard.domain.Appointment;
import cr.logisticaportuaria.yard.domain.AppointmentStatus;
import cr.logisticaportuaria.yard.domain.AppointmentType;
import cr.logisticaportuaria.yard.domain.Carrier;
import cr.logisticaportuaria.yard.domain.Container;
import cr.logisticaportuaria.yard.domain.ContainerStatus;
import cr.logisticaportuaria.yard.domain.ContainerType;
import cr.logisticaportuaria.yard.domain.Driver;
import cr.logisticaportuaria.yard.domain.Gate;
import cr.logisticaportuaria.yard.domain.GateKind;
import cr.logisticaportuaria.yard.domain.Truck;
import cr.logisticaportuaria.yard.infrastructure.mapper.YardMapper;
import cr.logisticaportuaria.yard.infrastructure.persistence.AppointmentRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.CarrierRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.DriverRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.GateRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.TruckRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD of the master data of the yard: carriers, drivers, trucks,
 * appointments, gates and containers.
 *
 * <p>Every change is registered in the audit trail.
 */
@Service
public class MasterDataService {

    private final CarrierRepository carrierRepository;
    private final DriverRepository driverRepository;
    private final TruckRepository truckRepository;
    private final AppointmentRepository appointmentRepository;
    private final GateRepository gateRepository;
    private final ContainerRepository containerRepository;
    private final YardMapper mapper;
    private final AuditService auditService;

    /**
     * Constructor.
     *
     * @param carrierRepository     repository of the carriers
     * @param driverRepository      repository of the drivers
     * @param truckRepository       repository of the trucks
     * @param appointmentRepository repository of the appointments
     * @param gateRepository        repository of the gates
     * @param containerRepository   repository of the containers
     * @param mapper                mapper of the DTOs
     * @param auditService          audit trail
     */
    public MasterDataService(CarrierRepository carrierRepository,
            DriverRepository driverRepository,
            TruckRepository truckRepository,
            AppointmentRepository appointmentRepository,
            GateRepository gateRepository,
            ContainerRepository containerRepository,
            YardMapper mapper,
            AuditService auditService) {
        this.carrierRepository = carrierRepository;
        this.driverRepository = driverRepository;
        this.truckRepository = truckRepository;
        this.appointmentRepository = appointmentRepository;
        this.gateRepository = gateRepository;
        this.containerRepository = containerRepository;
        this.mapper = mapper;
        this.auditService = auditService;
    }

    /**
     * @param active   whether only the active carriers are returned (null means both)
     * @param pageable pagination
     * @return the carriers
     */
    @Transactional(readOnly = true)
    public PageResponse<CarrierResponse> listCarriers(Boolean active, Pageable pageable) {
        Page<Carrier> page = carrierRepository.search(active, pageable);
        return PageResponse.of(page.map(mapper::toCarrierResponse));
    }

    /**
     * @param request body of the carrier
     * @return the created carrier
     */
    @Transactional
    public CarrierResponse createCarrier(CarrierRequest request) {
        Carrier carrier = new Carrier(request.code().trim(), request.name().trim(), request.taxId());
        Carrier saved = carrierRepository.save(carrier);
        audit("CREATE", "carrier", saved.getId(), Map.of("code", saved.getCode()));
        return mapper.toCarrierResponse(saved);
    }

    /**
     * @param id      identifier of the carrier
     * @param request body of the carrier
     * @return the updated carrier
     */
    @Transactional
    public CarrierResponse updateCarrier(UUID id, CarrierRequest request) {
        Carrier carrier = carrierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("transportista", id));
        carrier.setCode(request.code().trim());
        carrier.setName(request.name().trim());
        carrier.setTaxId(request.taxId());
        audit("UPDATE", "carrier", carrier.getId(), Map.of("code", carrier.getCode()));
        return mapper.toCarrierResponse(carrier);
    }

    /**
     * @param namePart part of the name of the driver
     * @param pageable pagination
     * @return the drivers
     */
    @Transactional(readOnly = true)
    public PageResponse<DriverResponse> listDrivers(String namePart, Pageable pageable) {
        Page<Driver> page = namePart == null || namePart.isBlank()
                ? driverRepository.findAll(pageable)
                : driverRepository.findByFullNameContainingIgnoreCase(namePart, pageable);
        return PageResponse.of(page.map(mapper::toDriverResponse));
    }

    /**
     * @param request body of the driver
     * @return the created driver
     */
    @Transactional
    public DriverResponse createDriver(DriverRequest request) {
        Driver driver = new Driver(request.fullName().trim(), request.licenseId(), request.phone());
        Driver saved = driverRepository.save(driver);
        audit("CREATE", "driver", saved.getId(), Map.of("nombre", saved.getFullName()));
        return mapper.toDriverResponse(saved);
    }

    /**
     * @param id      identifier of the driver
     * @param request body of the driver
     * @return the updated driver
     */
    @Transactional
    public DriverResponse updateDriver(UUID id, DriverRequest request) {
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("conductor", id));
        driver.setFullName(request.fullName().trim());
        driver.setLicenseId(request.licenseId());
        driver.setPhone(request.phone());
        audit("UPDATE", "driver", driver.getId(), Map.of("nombre", driver.getFullName()));
        return mapper.toDriverResponse(driver);
    }

    /**
     * @param carrierId carrier of the trucks (may be null)
     * @param active    whether only the active trucks are returned (null means both)
     * @param pageable  pagination
     * @return the trucks
     */
    @Transactional(readOnly = true)
    public PageResponse<TruckResponse> listTrucks(UUID carrierId, Boolean active, Pageable pageable) {
        Page<Truck> page = truckRepository.search(carrierId, active, pageable);
        return PageResponse.of(page.map(mapper::toTruckResponse));
    }

    /**
     * @param request body of the truck
     * @return the created truck
     */
    @Transactional
    public TruckResponse createTruck(TruckRequest request) {
        String plate = TextNormalizer.normalizePlate(request.plate());
        if (plate == null || plate.isBlank()) {
            throw new BusinessRuleException("La matrícula del camión es obligatoria.");
        }
        if (truckRepository.findByPlate(plate).isPresent()) {
            throw new BusinessRuleException("Ya existe un camión con la matrícula %s.".formatted(plate));
        }
        Truck truck = new Truck(plate, request.carrierId(), request.driverId());
        Truck saved = truckRepository.save(truck);
        audit("CREATE", "truck", saved.getId(), Map.of("matricula", saved.getPlate()));
        return mapper.toTruckResponse(saved);
    }

    /**
     * @param id      identifier of the truck
     * @param request body of the truck
     * @return the updated truck
     */
    @Transactional
    public TruckResponse updateTruck(UUID id, TruckRequest request) {
        Truck truck = truckRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("camión", id));
        String plate = TextNormalizer.normalizePlate(request.plate());
        if (plate == null || plate.isBlank()) {
            throw new BusinessRuleException("La matrícula del camión es obligatoria.");
        }
        truckRepository.findByPlate(plate)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new BusinessRuleException("Ya existe un camión con la matrícula %s.".formatted(plate));
                });
        truck.setPlate(plate);
        truck.setCarrierId(request.carrierId());
        truck.setDriverId(request.driverId());
        audit("UPDATE", "truck", truck.getId(), Map.of("matricula", truck.getPlate()));
        return mapper.toTruckResponse(truck);
    }

    /**
     * @param yardId    yard of the appointments
     * @param status    status of the appointments (may be null)
     * @param type      type of the appointments (may be null)
     * @param from      lower bound of the schedule (may be null)
     * @param to        upper bound of the schedule (may be null)
     * @param pageable  pagination
     * @return the appointments
     */
    @Transactional(readOnly = true)
    public PageResponse<AppointmentResponse> listAppointments(UUID yardId,
            AppointmentStatus status,
            AppointmentType type,
            Instant from,
            Instant to,
            Pageable pageable) {
        Page<Appointment> page = appointmentRepository.search(yardId, status, type, from, to, pageable);
        return PageResponse.of(page.map(mapper::toAppointmentResponse));
    }

    /**
     * @param request body of the appointment
     * @return the created appointment
     */
    @Transactional
    public AppointmentResponse createAppointment(AppointmentRequest request) {
        Appointment appointment = new Appointment(
                request.yardId(),
                request.gateId(),
                request.truckId(),
                parseAppointmentType(request.appointmentType()),
                request.scheduledAt(),
                request.windowMinutes() == null ? 30 : request.windowMinutes(),
                request.containerCode(),
                request.notes());
        Appointment saved = appointmentRepository.save(appointment);
        audit("CREATE", "appointment", saved.getId(),
                Map.of("tipo", saved.getAppointmentType().name(), "programada", saved.getScheduledAt().toString()));
        return mapper.toAppointmentResponse(saved);
    }

    /**
     * @param id      identifier of the appointment
     * @param request body of the appointment
     * @return the updated appointment
     */
    @Transactional
    public AppointmentResponse updateAppointment(UUID id, AppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("cita", id));
        appointment.setGateId(request.gateId());
        appointment.setTruckId(request.truckId());
        appointment.setAppointmentType(parseAppointmentType(request.appointmentType()));
        appointment.setScheduledAt(request.scheduledAt());
        if (request.windowMinutes() != null && request.windowMinutes() > 0) {
            appointment.setWindowMinutes(request.windowMinutes());
        }
        appointment.setContainerCode(request.containerCode());
        appointment.setNotes(request.notes());
        appointment.setUpdatedAt(Instant.now());
        audit("UPDATE", "appointment", appointment.getId(), Map.of("tipo", appointment.getAppointmentType().name()));
        return mapper.toAppointmentResponse(appointment);
    }

    /**
     * @param yardId yard of the gates (may be null)
     * @return the gates
     */
    @Transactional(readOnly = true)
    public List<GateResponse> listGates(UUID yardId) {
        List<Gate> gates = yardId == null
                ? gateRepository.findAll()
                : gateRepository.findByYardIdOrderByCode(yardId);
        return gates.stream().map(mapper::toGateResponse).toList();
    }

    /**
     * @param request body of the gate
     * @return the created gate
     */
    @Transactional
    public GateResponse createGate(GateRequest request) {
        Gate gate = new Gate(
                request.yardId(),
                request.code().trim(),
                request.name().trim(),
                parseGateKind(request.kind()),
                request.lanes() == null ? 1 : request.lanes());
        Gate saved = gateRepository.save(gate);
        audit("CREATE", "gate", saved.getId(), Map.of("codigo", saved.getCode()));
        return mapper.toGateResponse(saved);
    }

    /**
     * @param id      identifier of the gate
     * @param request body of the gate
     * @return the updated gate
     */
    @Transactional
    public GateResponse updateGate(UUID id, GateRequest request) {
        Gate gate = gateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("puerta", id));
        gate.setCode(request.code().trim());
        gate.setName(request.name().trim());
        gate.setKind(parseGateKind(request.kind()));
        if (request.lanes() != null && request.lanes() > 0) {
            gate.setLanes(request.lanes());
        }
        audit("UPDATE", "gate", gate.getId(), Map.of("codigo", gate.getCode()));
        return mapper.toGateResponse(gate);
    }

    /**
     * @param request body of the container
     * @return the created container
     */
    @Transactional
    public ContainerResponse createContainer(ContainerRequest request) {
        String code = normalizeContainerCode(request.code());
        if (containerRepository.findByCode(code).isPresent()) {
            throw new BusinessRuleException("Ya existe un contenedor con el código %s.".formatted(code));
        }
        Container container = new Container(
                code,
                request.sizeFt() == null ? 20 : request.sizeFt(),
                parseContainerType(request.containerType()),
                parseContainerStatus(request.status()),
                request.clientCode(),
                request.eta(),
                request.etd(),
                Boolean.TRUE.equals(request.requiresPower()));
        Container saved = containerRepository.save(container);
        audit("CREATE", "container", saved.getId(), Map.of("codigo", saved.getCode()));
        return mapper.toContainerResponse(saved);
    }

    /**
     * @param code    BIC code of the container
     * @param request body of the container
     * @return the updated container
     */
    @Transactional
    public ContainerResponse updateContainer(String code, ContainerRequest request) {
        Container container = containerRepository.findByCode(normalizeContainerCode(code))
                .orElseThrow(() -> new NotFoundException("contenedor", code));
        if (request.sizeFt() != null) {
            container.setSizeFt(request.sizeFt());
        }
        if (request.containerType() != null) {
            container.setContainerType(parseContainerType(request.containerType()));
        }
        if (request.status() != null) {
            container.setStatus(parseContainerStatus(request.status()));
        }
        if (request.weightKg() != null) {
            container.setWeightKg(request.weightKg());
        }
        if (request.clientCode() != null) {
            container.setClientCode(request.clientCode());
        }
        if (request.eta() != null) {
            container.setEta(request.eta());
        }
        if (request.etd() != null) {
            container.setEtd(request.etd());
        }
        if (request.requiresPower() != null) {
            container.setRequiresPower(request.requiresPower());
        }
        container.setUpdatedAt(Instant.now());
        audit("UPDATE", "container", container.getId(), Map.of("codigo", container.getCode()));
        return mapper.toContainerResponse(container);
    }

    private String normalizeContainerCode(String code) {
        String normalized = TextNormalizer.normalizeContainer(code);
        if (normalized == null || !Iso6346.isValid(normalized)) {
            throw new BusinessRuleException(
                    "El código '%s' no cumple la norma ISO 6346 (11 caracteres y dígito de control válido)."
                            .formatted(code));
        }
        return normalized;
    }

    private AppointmentType parseAppointmentType(String type) {
        try {
            return AppointmentType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new BusinessRuleException("El tipo de cita '%s' no es válido (INGRESO o RETIRO).".formatted(type));
        }
    }

    private GateKind parseGateKind(String kind) {
        try {
            return GateKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new BusinessRuleException("El tipo de puerta '%s' no es válido (IN, OUT o BOTH).".formatted(kind));
        }
    }

    private ContainerType parseContainerType(String type) {
        try {
            return ContainerType.valueOf(type.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new BusinessRuleException("El tipo de contenedor '%s' no es válido (dry, reefer o hazmat)."
                    .formatted(type));
        }
    }

    private ContainerStatus parseContainerStatus(String status) {
        try {
            return ContainerStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new BusinessRuleException("El estado de contenedor '%s' no es válido.".formatted(status));
        }
    }

    private void audit(String action, String entityType, UUID entityId, Map<String, Object> payload) {
        auditService.record(CurrentActor.name(), action, entityType, entityId, null, payload);
    }
}
