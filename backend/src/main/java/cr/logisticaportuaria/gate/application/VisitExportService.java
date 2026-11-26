package cr.logisticaportuaria.gate.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import cr.logisticaportuaria.gate.domain.VisitLog;
import cr.logisticaportuaria.gate.domain.VisitState;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Exports of the digital logbook: a printable receipt (PDF with a QR code)
 * and a spreadsheet friendly CSV.
 *
 * <p>The receipt replaces the paper document of the gate: it identifies the
 * visit with a QR code that the operator can scan from the frontend.
 */
@Service
public class VisitExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisitExportService.class);

    /** Timezone used to print the instants of the receipt. */
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("America/Costa_Rica");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(DISPLAY_ZONE);

    private static final int QR_SIZE = 180;
    private static final float MARGIN = 50f;

    /**
     * Builds the printable receipt of a visit.
     *
     * @param visit    visit to print
     * @param yardName name of the yard (shown in the header)
     * @return the bytes of the PDF
     */
    public byte[] toPdf(VisitLog visit, String yardName) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                PDType1Font title = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
                PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

                content.beginText();
                content.setFont(title, 18);
                content.newLineAtOffset(MARGIN, 760);
                content.showText("Recibo de acceso - LogisticaPortuaria CR");
                content.setFont(regular, 11);
                content.newLineAtOffset(0, -22);
                content.showText("Patio: " + safe(yardName));
                content.newLineAtOffset(0, -16);
                content.showText("Visita: " + visit.getId());
                content.newLineAtOffset(0, -16);
                content.showText("Estado: " + visit.getState() + "   Resultado: " + visit.getResult());
                content.newLineAtOffset(0, -16);
                content.showText("Matricula: " + safe(visit.getPlate()) + "   Contenedor: "
                        + safe(visit.getContainerCode()));
                content.newLineAtOffset(0, -16);
                content.showText("Cita: " + (visit.getAppointmentId() == null ? "-" : visit.getAppointmentId()));
                content.newLineAtOffset(0, -16);
                content.showText("Ingreso: " + DATE_TIME.format(visit.getEnteredAt()));
                if (visit.getExitedAt() != null) {
                    content.newLineAtOffset(0, -16);
                    content.showText("Salida: " + DATE_TIME.format(visit.getExitedAt())
                            + "   Espera: " + seconds(visit.getWaitSeconds()) + "s"
                            + "   En patio: " + seconds(visit.getYardSeconds()) + "s");
                }
                if (visit.getReason() != null && !visit.getReason().isBlank()) {
                    content.newLineAtOffset(0, -16);
                    content.showText("Motivo: " + sanitize(visit.getReason()));
                }
                content.endText();

                PDImageXObject qr = qrCode(document, qrContent(visit));
                content.drawImage(qr, PDRectangle.A4.getWidth() - MARGIN - QR_SIZE, 560, QR_SIZE, QR_SIZE);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException | WriterException exception) {
            LOGGER.error("No se pudo generar el PDF de la visita {}: {}", visit.getId(), exception.getMessage());
            throw new IllegalStateException("No se pudo generar el comprobante de la visita.", exception);
        }
    }

    /**
     * Builds the CSV of a list of visits.
     *
     * @param visits visits to export
     * @return the bytes of the CSV (UTF-8 with BOM, ready for a spreadsheet)
     */
    public byte[] toCsv(List<VisitLog> visits) {
        StringBuilder builder = new StringBuilder();
        builder.append('﻿');
        builder.append("id,fecha_ingreso,estado,resultado,direccion,matricula,contenedor,cita,puerta,")
                .append("espera_s,patio_s,servicio_s,motivo")
                .append('\n');
        for (VisitLog visit : visits) {
            builder.append(csv(visit.getId().toString())).append(',')
                    .append(csv(DATE_TIME.format(visit.getEnteredAt()))).append(',')
                    .append(csv(visit.getState().name())).append(',')
                    .append(csv(visit.getResult().name())).append(',')
                    .append(csv(visit.getDirection().name())).append(',')
                    .append(csv(visit.getPlate())).append(',')
                    .append(csv(visit.getContainerCode())).append(',')
                    .append(csv(visit.getAppointmentId() == null ? null : visit.getAppointmentId().toString()))
                    .append(',')
                    .append(csv(visit.getGateId().toString())).append(',')
                    .append(csv(visit.getWaitSeconds())).append(',')
                    .append(csv(visit.getYardSeconds())).append(',')
                    .append(csv(visit.getServiceSeconds())).append(',')
                    .append(csv(visit.getReason()))
                    .append('\n');
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Content encoded in the QR code of the receipt.
     *
     * @param visit visit of the receipt
     * @return the content of the QR code
     */
    public String qrContent(VisitLog visit) {
        return "LPCR1|visitId=" + visit.getId()
                + "|plate=" + safe(visit.getPlate())
                + "|container=" + safe(visit.getContainerCode())
                + "|state=" + visit.getState()
                + "|result=" + visit.getResult()
                + "|at=" + visit.getEnteredAt();
    }

    private PDImageXObject qrCode(PDDocument document, String content) throws IOException, WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        return LosslessFactory.createFromImage(document, image);
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private String safe(Object value) {
        return value == null || value.toString().isBlank() ? "-" : value.toString();
    }

    private String seconds(Integer value) {
        return value == null ? "-" : value.toString();
    }

    private String sanitize(String text) {
        String cleaned = text.replaceAll("[^\\x20-\\x7E]", "?");
        return cleaned.length() > 90 ? cleaned.substring(0, 90) + "..." : cleaned;
    }

    /**
     * @param state state of a visit
     * @return true when the visit is still open
     */
    public static boolean isOpen(VisitState state) {
        return state == VisitState.ENTRADA_PUERTA || state == VisitState.VALIDADO
                || state == VisitState.ASIGNADO || state == VisitState.EN_PROCESO;
    }
}
