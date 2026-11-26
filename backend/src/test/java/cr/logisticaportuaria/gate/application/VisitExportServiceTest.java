package cr.logisticaportuaria.gate.application;

import static org.assertj.core.api.Assertions.assertThat;

import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitResult;
import cr.logisticaportuaria.gate.domain.VisitState;
import cr.logisticaportuaria.shared.domain.Direction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the exports of the digital logbook (PDF with QR and CSV).
 */
class VisitExportServiceTest {

    private final VisitExportService exportService = new VisitExportService();

    @Test
    @DisplayName("El comprobante en PDF se genera con el tamaño esperado de un PDF")
    void generatesPdf() {
        byte[] pdf = exportService.toPdf(visit(), "Patio de prueba");

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("El contenido del QR identifica la visita")
    void qrContentIdentifiesTheVisit() {
        VisitLog visit = visit();
        String content = exportService.qrContent(visit);

        assertThat(content).startsWith("LPCR1|visitId=" + visit.getId());
        assertThat(content).contains("plate=ABC123");
        assertThat(content).contains("container=CSQU3054383");
        assertThat(content).contains("state=" + visit.getState());
    }

    @Test
    @DisplayName("El CSV tiene el encabezado y una fila por visita")
    void generatesCsv() {
        byte[] csv = exportService.toCsv(List.of(visit(), visit()));

        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("id,fecha_ingreso,estado,resultado,direccion,matricula,contenedor");
        assertThat(text.split("\n")).hasSize(3);
        assertThat(text).contains("ABC123").contains("CSQU3054383").contains("VALIDADO");
    }

    @Test
    @DisplayName("El CSV escapa las comas de los textos")
    void escapesCsvValues() {
        VisitLog visit = visit();
        visit.setReason("Conteo, con coma");

        String text = new String(exportService.toCsv(List.of(visit)), StandardCharsets.UTF_8);

        assertThat(text).contains("\"Conteo, con coma\"");
    }

    @Test
    @DisplayName("Una visita abierta es la que no está cerrada")
    void detectsOpenVisits() {
        assertThat(VisitExportService.isOpen(VisitState.ENTRADA_PUERTA)).isTrue();
        assertThat(VisitExportService.isOpen(VisitState.VALIDADO)).isTrue();
        assertThat(VisitExportService.isOpen(VisitState.ASIGNADO)).isTrue();
        assertThat(VisitExportService.isOpen(VisitState.EN_PROCESO)).isTrue();
        assertThat(VisitExportService.isOpen(VisitState.SALIDA_PUERTA)).isFalse();
        assertThat(VisitExportService.isOpen(VisitState.CANCELADO)).isFalse();
    }

    private VisitLog visit() {
        VisitLog visit = new VisitLog(UUID.randomUUID(), UUID.randomUUID(), Direction.IN,
                Instant.parse("2026-02-22T12:00:00Z"));
        visit.setPlate("ABC123");
        visit.setContainerCode("CSQU3054383");
        visit.setResult(VisitResult.AUTO_APROBADO);
        visit.transitionTo(VisitState.VALIDADO, Instant.parse("2026-02-22T12:01:00Z"));
        return visit;
    }
}
