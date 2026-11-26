package cr.logisticaportuaria.optimizer.congestion.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the congestion module ({@code app.congestion.*}).
 */
@Validated
@ConfigurationProperties(prefix = "app.congestion")
public class CongestionProperties {

    /** Yard of the module; {@code null} means every yard (the demo runs a single one). */
    private UUID yardId;

    /** Whether the scheduled refresh runs. */
    private boolean schedulingEnabled = true;

    /** Milliseconds between scheduled refreshes (5 minutes by default). */
    @Min(10_000)
    private long refreshMillis = 300_000L;

    /** Delay before the first scheduled refresh. */
    @Min(0)
    private long initialDelayMillis = 60_000L;

    /** Days of history used to fit the model. */
    @Min(2)
    @Max(180)
    private int historyDays = 28;

    /** Minutes the trained model is reused before retraining. */
    @Min(1)
    private int retrainMinutes = 60;

    /** Days of forecasts kept in {@code congestion_forecast}. */
    @Min(1)
    private int retentionDays = 7;

    /** Wait (minutes) from which the risk is MEDIO. */
    @Min(1)
    private int mediumRiskMinutes = 15;

    /** Wait (minutes) from which the risk is ALTO. */
    @Min(2)
    private int highRiskMinutes = 30;

    /** Whether the MQTT alerts are published. */
    private boolean alertsEnabled = true;

    /** Topic of the alerts; {@code %s} is replaced with the yard identifier. */
    private String alertTopic = "yard/%s/alerts/congestion";

    /** Buckets per season of the arrival smoothing (96 = one day of 15-minute buckets). */
    @Min(24)
    @Max(672)
    private int seasonLength = 96;

    /** Maximum number of buckets used to fit the model of one gate. */
    @Min(40)
    @Max(20_000)
    private int maxTrainingRows = 2000;

    public UUID getYardId() {
        return yardId;
    }

    public void setYardId(UUID yardId) {
        this.yardId = yardId;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public long getRefreshMillis() {
        return refreshMillis;
    }

    public void setRefreshMillis(long refreshMillis) {
        this.refreshMillis = refreshMillis;
    }

    public long getInitialDelayMillis() {
        return initialDelayMillis;
    }

    public void setInitialDelayMillis(long initialDelayMillis) {
        this.initialDelayMillis = initialDelayMillis;
    }

    public int getHistoryDays() {
        return historyDays;
    }

    public void setHistoryDays(int historyDays) {
        this.historyDays = historyDays;
    }

    public int getRetrainMinutes() {
        return retrainMinutes;
    }

    public void setRetrainMinutes(int retrainMinutes) {
        this.retrainMinutes = retrainMinutes;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getMediumRiskMinutes() {
        return mediumRiskMinutes;
    }

    public void setMediumRiskMinutes(int mediumRiskMinutes) {
        this.mediumRiskMinutes = mediumRiskMinutes;
    }

    public int getHighRiskMinutes() {
        return highRiskMinutes;
    }

    public void setHighRiskMinutes(int highRiskMinutes) {
        this.highRiskMinutes = highRiskMinutes;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public void setAlertsEnabled(boolean alertsEnabled) {
        this.alertsEnabled = alertsEnabled;
    }

    public String getAlertTopic() {
        return alertTopic;
    }

    public void setAlertTopic(String alertTopic) {
        this.alertTopic = alertTopic;
    }

    public int getSeasonLength() {
        return seasonLength;
    }

    public void setSeasonLength(int seasonLength) {
        this.seasonLength = seasonLength;
    }

    public int getMaxTrainingRows() {
        return maxTrainingRows;
    }

    public void setMaxTrainingRows(int maxTrainingRows) {
        this.maxTrainingRows = maxTrainingRows;
    }
}
