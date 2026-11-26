package cr.logisticaportuaria.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cr.logisticaportuaria.fleet.application.dto.CreateWorkOrderRequest;
import cr.logisticaportuaria.fleet.application.dto.WorkOrderResponse;
import cr.logisticaportuaria.fleet.domain.FleetStatus;
import cr.logisticaportuaria.fleet.infrastructure.persistence.FleetVehicleRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository;
import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository.FreeVehicle;
import cr.logisticaportuaria.fleet.infrastructure.persistence.WorkOrderRepository.WorkOrderRow;
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
 * Unit tests of the lifecycle and the assignment heuristic of the work orders.
 */
class WorkOrderServiceTest {

    private static final UUID YARD = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID V1 = UUID.randomUUID();
    private static final UUID V2 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private final WorkOrderRepository repository = mock(WorkOrderRepository.class);
    private final FleetVehicleRepository vehicleRepository = mock(FleetVehicleRepository.class);
    private final FleetQueryService queryService = mock(FleetQueryService.class);
    private final GeofenceCache geofenceCache = mock(GeofenceCache.class);
    private final FleetStateCache stateCache = mock(FleetStateCache.class);

    private final WorkOrderService service = new WorkOrderService(repository, vehicleRepository,
            queryService, geofenceCache, stateCache);

    @BeforeEach
    void setUp() {
        when(queryService.resolveYard(any())).thenReturn(YARD);
    }

    @Test
    @DisplayName("asigna el vehiculo libre mas cercano al slot de origen")
    void assignsTheNearestFreeVehicle() {
        when(repository.findById(ORDER))
                .thenReturn(Optional.of(order(WorkOrderRepository.PENDIENTE, null, 5)))
                .thenReturn(Optional.of(order(WorkOrderRepository.ASIGNADA, V1, 5)));
        when(repository.findFreeVehicles(YARD)).thenReturn(List.of(
                new FreeVehicle(V1, "VEH-01", 10, 10),
                new FreeVehicle(V2, "VEH-02", 400, 90)));

        WorkOrderResponse response = service.assign(ORDER, null);

        verify(repository).assign(ORDER, V1);
        verify(vehicleRepository).updateStatus(V1, FleetStatus.EN_MOVIMIENTO);
        assertThat(response.status()).isEqualTo(WorkOrderRepository.ASIGNADA);
        assertThat(response.vehicleId()).isEqualTo(V1);
    }

    @Test
    @DisplayName("un vehiculo explicito no libre produce 409")
    void rejectsNonFreeVehicle() {
        when(repository.findById(ORDER)).thenReturn(Optional.of(order(
                WorkOrderRepository.PENDIENTE, null, 5)));
        when(repository.findFreeVehicles(YARD)).thenReturn(List.of(new FreeVehicle(V1, "VEH-01", 0, 0)));

        assertThatThrownBy(() -> service.assign(ORDER, V2))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).assign(any(), any());
    }

    @Test
    @DisplayName("sin vehiculos libres no se puede asignar")
    void noFreeVehicles() {
        when(repository.findById(ORDER)).thenReturn(Optional.of(order(
                WorkOrderRepository.PENDIENTE, null, 5)));
        when(repository.findFreeVehicles(YARD)).thenReturn(List.of());

        assertThatThrownBy(() -> service.assign(ORDER, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("iniciar una orden pendiente produce 409")
    void invalidTransition() {
        when(repository.findById(ORDER)).thenReturn(Optional.of(order(
                WorkOrderRepository.PENDIENTE, null, 5)));

        assertThatThrownBy(() -> service.start(ORDER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).start(any(), any());
    }

    @Test
    @DisplayName("completar libera el vehiculo")
    void completeFreesTheVehicle() {
        when(repository.findById(ORDER))
                .thenReturn(Optional.of(order(WorkOrderRepository.EN_CURSO, V1, 5)))
                .thenReturn(Optional.of(order(WorkOrderRepository.COMPLETADA, V1, 5)));

        service.complete(ORDER);

        verify(repository).complete(eq(ORDER), any());
        verify(vehicleRepository).updateStatus(V1, FleetStatus.LIBRE);
    }

    @Test
    @DisplayName("assign-next elige la orden mas prioritaria")
    void assignNextDispatchesTheMostUrgent() {
        UUID urgent = UUID.randomUUID();
        when(repository.findByYard(YARD, WorkOrderRepository.PENDIENTE)).thenReturn(List.of(
                order(ORDER, WorkOrderRepository.PENDIENTE, null, 5, NOW),
                order(urgent, WorkOrderRepository.PENDIENTE, null, 1, NOW.plusSeconds(10))));
        when(repository.findById(urgent))
                .thenReturn(Optional.of(order(urgent, WorkOrderRepository.PENDIENTE, null, 1, NOW)))
                .thenReturn(Optional.of(order(urgent, WorkOrderRepository.ASIGNADA, V2, 1, NOW)));
        when(repository.findFreeVehicles(YARD)).thenReturn(List.of(
                new FreeVehicle(V1, "VEH-01", 10, 10),
                new FreeVehicle(V2, "VEH-02", 60, 20)));

        WorkOrderResponse response = service.assignNext(YARD);

        assertThat(response.id()).isEqualTo(urgent);
        verify(repository).assign(urgent, V2);
    }

    @Test
    @DisplayName("un tipo de orden desconocido produce 400")
    void invalidOrderType() {
        assertThatThrownBy(() -> service.create(new CreateWorkOrderRequest(YARD, null, "VIAJE",
                null, null, 5)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("una prioridad fuera de rango produce 400")
    void invalidPriority() {
        assertThatThrownBy(() -> service.create(new CreateWorkOrderRequest(YARD, "CSQU3054383",
                "MOVIMIENTO", null, null, 0)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).create(any(), any(), any(), any(), any(), anyInt());
    }

    private static WorkOrderRow order(String status, UUID vehicle, int priority) {
        return order(ORDER, status, vehicle, priority, NOW);
    }

    private static WorkOrderRow order(UUID id, String status, UUID vehicle, int priority,
            Instant createdAt) {
        return new WorkOrderRow(id, YARD, vehicle, vehicle == null ? null : "VEH-01", "CSQU3054383",
                "MOVIMIENTO", null, null, priority, status, createdAt, null, null);
    }
}
