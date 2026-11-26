package cr.logisticaportuaria.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cr.logisticaportuaria.fleet.application.dto.FleetKpisResponse;
import cr.logisticaportuaria.fleet.application.dto.VehicleResponse;
import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.domain.VehicleInfo;
import cr.logisticaportuaria.fleet.domain.VehicleState;
import cr.logisticaportuaria.fleet.infrastructure.persistence.ContainerTraceRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.FleetVehicleRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.VehiclePositionRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.VehiclePositionRepository.KpiSample;
import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests of the KPI aggregation and the validation of the read queries.
 */
class FleetQueryServiceTest {

    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID VEHICLE = UUID.fromString("70000000-0000-7000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");

    private final FleetProperties properties = new FleetProperties();
    private final VehicleDirectory directory = mock(VehicleDirectory.class);
    private final FleetStateCache stateCache = mock(FleetStateCache.class);
    private final FleetVehicleRepository vehicleRepository = mock(FleetVehicleRepository.class);
    private final VehiclePositionRepository positionRepository = mock(VehiclePositionRepository.class);
    private final WorkOrderRepository workOrderRepository = mock(WorkOrderRepository.class);
    private final ContainerTraceRepository traceRepository = mock(ContainerTraceRepository.class);
    private final FleetAlertBuffer alertBuffer = mock(FleetAlertBuffer.class);
    private final FleetMetrics metrics = new FleetMetrics();

    private final FleetQueryService service = new FleetQueryService(properties, directory, stateCache,
            vehicleRepository, positionRepository, workOrderRepository, traceRepository,
            alertBuffer, metrics);

    @BeforeEach
    void setUp() {
        when(vehicleRepository.singleYard()).thenReturn(Optional.of(YARD));
    }

    @Test
    @DisplayName("utilizacion, tiempo libre y distancia a partir de las muestras")
    void kpisAggregateTheSamples() {
        when(positionRepository.findSamples(any(), any(), any(), anyInt())).thenReturn(List.of(
                new KpiSample(VEHICLE, T0, 0, 0, 0, FleetStatus.LIBRE),
                new KpiSample(VEHICLE, T0.plusSeconds(10), 0, 10, 6, FleetStatus.EN_MOVIMIENTO),
                new KpiSample(VEHICLE, T0.plusSeconds(20), 0, 20, 6, FleetStatus.EN_MOVIMIENTO)));
        when(workOrderRepository.averageCycleMinutes(any(), any(), any())).thenReturn(12.5);
        when(workOrderRepository.countCompleted(any(), any(), any())).thenReturn(2L);
        when(alertBuffer.countByType(any())).thenReturn(java.util.Map.of("RALENTI", 3L));

        FleetKpisResponse kpis = service.kpis(YARD, T0, T0.plusSeconds(60));

        assertThat(kpis.utilizationPct()).isEqualTo(50.0);
        assertThat(kpis.idlePct()).isEqualTo(50.0);
        assertThat(kpis.maintenancePct()).isZero();
        assertThat(kpis.distanceKm()).isEqualTo(0.02);
        assertThat(kpis.averageCycleMinutes()).isEqualTo(12.5);
        assertThat(kpis.completedWorkOrders()).isEqualTo(2);
        assertThat(kpis.alertsByType()).containsEntry("RALENTI", 3L);
        assertThat(kpis.vehicles()).isEqualTo(1);
    }

    @Test
    @DisplayName("un rango invertido produce 400")
    void invalidRange() {
        assertThatThrownBy(() -> service.kpis(YARD, T0.plusSeconds(60), T0))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("el listado usa el ultimo estado persistido y marca los silenciosos")
    void vehiclesUseTheLastState() {
        when(directory.vehicles()).thenReturn(List.of(new VehicleInfo(VEHICLE, YARD, "VEH-01",
                "MONTACARGAS", FleetStatus.LIBRE)));
        when(stateCache.get(VEHICLE)).thenReturn(Optional.empty());
        when(positionRepository.findLastState(VEHICLE)).thenReturn(Optional.of(new VehicleState(
                VEHICLE, YARD, "VEH-01", "MONTACARGAS", FleetStatus.EN_MOVIMIENTO,
                Instant.now().minusSeconds(600), 10, 5, 3, 80.0, null, null, null)));

        List<VehicleResponse> vehicles = service.vehicles(YARD, null);

        assertThat(vehicles).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.code()).isEqualTo("VEH-01");
            assertThat(vehicle.status()).isEqualTo("EN_MOVIMIENTO");
            assertThat(vehicle.stale()).isTrue();
        });
    }

    @Test
    @DisplayName("un estado desconocido en el filtro produce 400")
    void invalidStatusFilter() {
        assertThatThrownBy(() -> service.vehicles(YARD, "VOLANDO"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("el track de un vehiculo inexistente produce 404")
    void trackOfUnknownVehicle() {
        when(directory.byId(VEHICLE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.track(VEHICLE, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("la traza de un contenedor inexistente produce 404")
    void traceOfUnknownContainer() {
        when(traceRepository.findContainerId("CSQU3054383")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.trace("csqu 3054383"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
