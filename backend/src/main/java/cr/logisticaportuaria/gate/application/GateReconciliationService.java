package cr.logisticaportuaria.gate.application;

import cr.logisticaportuaria.gate.domain.GateEvent;
import cr.logisticaportuaria.gate.domain.GateReconciliationRules;
import cr.logisticaportuaria.gate.domain.ReadingKind;
import cr.logisticaportuaria.gate.domain.ReconciliationContext;
import cr.logisticaportuaria.gate.domain.ReconciliationDecision;
import cr.logisticaportuaria.gate.domain.ReconciliationPolicy;
import cr.logisticaportuaria.yard.domain.Appointment;
import cr.logisticaportuaria.yard.domain.AppointmentStatus;

import cr.logisticaportuaria.yard.domain.Truck;
import cr.logisticaportuaria.yard.infrastructure.persistence.AppointmentRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.ContainerRepository;
import cr.logisticaportuaria.yard.infrastructure.persistence.TruckRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatic reconciliation of the readings: matches the plate with an
 * appointment in force and the BIC code with the expected container.
 *
 * <p>
 * The rules themselves live in {@link GateReconciliationRules} (pure
 * functions); this service only gathers the data and delegates.
 */
@Service
public class GateReconciliationService {

        private static final Logger LOGGER = LoggerFactory.getLogger(GateReconciliationService.class);

        /** Broadest period used to look for the candidates of an appointment. */
        private static final int CANDIDATE_LOOKBACK_HOURS = 6;

        private final AppointmentRepository appointmentRepository;
        private final ContainerRepository containerRepository;
        private final TruckRepository truckRepository;
        private final GateProperties properties;

        /**
         * Constructor.
         *
         * @param appointmentRepository repository of the appointments
         * @param containerRepository   repository of the containers
         * @param truckRepository       repository of the trucks
         * @param properties            configuration of the gate module
         */
        public GateReconciliationService(AppointmentRepository appointmentRepository,
                        ContainerRepository containerRepository,
                        TruckRepository truckRepository,
                        GateProperties properties) {
                this.appointmentRepository = appointmentRepository;
                this.containerRepository = containerRepository;
                this.truckRepository = truckRepository;
                this.properties = properties;
        }

        /**
         * Reconciles a reading.
         *
         * @param event the reading
         * @return the decision with its explanation in Spanish
         */
        @Transactional(readOnly = true)
        public ReconciliationDecision reconcile(GateEvent event) {
                ReconciliationPolicy policy = properties.toPolicy();
                ReadingKind kind = event.getKind();
                String text = event.getNormalizedText();

                ReconciliationContext.AppointmentMatch appointment = null;

                if (kind == ReadingKind.PLATE) {
                        Optional<Truck> truckOptional = text == null ? Optional.empty()
                                        : truckRepository.findByPlate(text);
                        if (truckOptional.isPresent()) {
                                appointment = matchAppointmentOfTruck(truckOptional.get().getId(),
                                                event.getObservedAt(), policy);
                        }
                } else {
                        appointment = matchAppointmentOfContainer(text, event.getObservedAt(), policy);
                }

                ReconciliationContext.ContainerMatch container = null;
                if (kind == ReadingKind.CONTAINER && text != null) {
                        container = containerRepository.findByCode(text)
                                        .map(found -> new ReconciliationContext.ContainerMatch(
                                                        found.getId(), found.getCode(), found.getStatus()))
                                        .orElse(null);
                }

                ReconciliationContext context = new ReconciliationContext(
                                kind,
                                text,
                                event.getConfidence() == null ? null : event.getConfidence().doubleValue(),
                                event.getIsoValid(),
                                event.isCorrected(),
                                event.getObservedAt(),
                                appointment,
                                container,
                                policy);

                ReconciliationDecision decision = GateReconciliationRules.evaluate(context);
                LOGGER.debug("Lectura {} conciliada como {}", event.getEventId(), decision.result());
                return decision;
        }

        /**
         * Finds the appointment of a truck that covers the instant of the reading.
         *
         * @param truckId    truck of the reading
         * @param observedAt instant of the reading
         * @param policy     parameters of the reconciliation
         * @return the appointment, when there is one in the window
         */
        private ReconciliationContext.AppointmentMatch matchAppointmentOfTruck(java.util.UUID truckId,
                        Instant observedAt,
                        ReconciliationPolicy policy) {
                List<Appointment> candidates = appointmentRepository.findByTruckIdAndScheduledAtBetween(
                                truckId,
                                observedAt.minus(Duration.ofHours(CANDIDATE_LOOKBACK_HOURS)),
                                observedAt.plus(Duration.ofHours(CANDIDATE_LOOKBACK_HOURS)));
                return candidates.stream()
                                .filter(this::isActive)
                                .filter(appointment -> GateReconciliationRules.withinWindow(
                                                observedAt, appointment.getScheduledAt(),
                                                windowOf(appointment, policy)))
                                .min(Comparator.comparing(appointment -> Math.abs(Duration.between(
                                                appointment.getScheduledAt(), observedAt).toMinutes())))
                                .map(appointment -> toMatch(appointment,
                                                truckRepository.findById(appointment.getTruckId())
                                                                .map(Truck::getPlate)
                                                                .orElse(null)))
                                .orElse(null);
        }

        /**
         * Finds the appointment that expects a container (a pick up) covering the
         * instant of the reading.
         *
         * @param containerCode container read at the gate
         * @param observedAt    instant of the reading
         * @param policy        parameters of the reconciliation
         * @return the appointment, when there is one in the window
         */
        private ReconciliationContext.AppointmentMatch matchAppointmentOfContainer(String containerCode,
                        Instant observedAt,
                        ReconciliationPolicy policy) {
                if (containerCode == null || containerCode.isBlank()) {
                        return null;
                }
                List<Appointment> candidates = appointmentRepository.findByContainerCodeIgnoreCase(containerCode);
                return candidates.stream()
                                .filter(this::isActive)
                                .filter(appointment -> GateReconciliationRules.withinWindow(
                                                observedAt, appointment.getScheduledAt(),
                                                windowOf(appointment, policy)))
                                .min(Comparator.comparing(appointment -> Math.abs(Duration.between(
                                                appointment.getScheduledAt(), observedAt).toMinutes())))
                                .map(appointment -> toMatch(appointment,
                                                appointment.getTruckId() == null
                                                                ? null
                                                                : truckRepository.findById(appointment.getTruckId())
                                                                                .map(Truck::getPlate)
                                                                                .orElse(null)))
                                .orElse(null);
        }

        private boolean isActive(Appointment appointment) {
                return appointment.getStatus() == AppointmentStatus.PENDIENTE
                                || appointment.getStatus() == AppointmentStatus.EN_CURSO;
        }

        private int windowOf(Appointment appointment, ReconciliationPolicy policy) {
                return appointment.getWindowMinutes() > 0 ? appointment.getWindowMinutes()
                                : policy.defaultWindowMinutes();
        }

        private ReconciliationContext.AppointmentMatch toMatch(Appointment appointment, String plate) {
                return new ReconciliationContext.AppointmentMatch(
                                appointment.getId(),
                                appointment.getTruckId(),
                                plate,
                                appointment.getAppointmentType(),
                                appointment.getScheduledAt(),
                                appointment.getWindowMinutes(),
                                appointment.getContainerCode());
        }
}
