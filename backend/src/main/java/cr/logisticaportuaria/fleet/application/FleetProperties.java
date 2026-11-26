package cr.logisticaportuaria.fleet.application;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the fleet module ({@code app.fleet.*}).
 */
@ConfigurationProperties("app.fleet")
public class FleetProperties {

    /** Yard used by the simulator and by the default queries; empty means every yard. */
    private UUID yardId;

    /** Whether the backend subscribes to the telemetry topic. */
    private boolean mqttEnabled = true;

    /** Topic of the telemetry, with wildcards for the yard and the vehicle. */
    private String telemetryTopic = "yard/+/fleet/+/telemetry";

    /** Whether the silence monitor, the broadcaster and the retention job run. */
    private boolean schedulingEnabled = true;

    /** Physical maximum speed used to reject impossible jumps, in km/h. */
    private double maxSpeedKmh = 40.0;

    /** Speed above which a speed alert is raised, in km/h. */
    private double speedLimitKmh = 15.0;

    /** Minutes below {@code idleSpeedKmh} that count as a prolonged idle. */
    private int idleMinutes = 10;

    /** Speed below which the vehicle is considered stopped, in km/h. */
    private double idleSpeedKmh = 1.0;

    /** Battery percentage that raises a low battery alert. */
    private double batteryLowPct = 20.0;

    /** Seconds without telemetry that raise a signal loss alert. */
    private int silenceSeconds = 60;

    /** Minutes before the same alert of the same vehicle can be raised again. */
    private int alertCooldownMinutes = 10;

    /** Interval of the aggregated WebSocket broadcast, in milliseconds. */
    private int broadcastIntervalMs = 1000;

    /** Days of raw positions kept in {@code vehicle_position}. */
    private int retentionDays = 30;

    /** Hours after which the positions are downsampled. */
    private int downsampleAfterHours = 24;

    /** One point every N seconds is kept after {@code downsampleAfterHours}. */
    private int downsampleSeconds = 10;

    /** Seconds before the geofences of a yard are reloaded. */
    private int geofenceRefreshSeconds = 60;

    /** Maximum number of points returned by the KPI aggregation. */
    private int kpiMaxPoints = 50000;

    /** Maximum number of points returned by a track. */
    private int trackMaxPoints = 5000;

    /** Maximum number of alerts kept in memory for the KPIs. */
    private int alertBufferSize = 10000;

    public UUID getYardId() {
        return yardId;
    }

    public void setYardId(UUID yardId) {
        this.yardId = yardId;
    }

    public boolean isMqttEnabled() {
        return mqttEnabled;
    }

    public void setMqttEnabled(boolean mqttEnabled) {
        this.mqttEnabled = mqttEnabled;
    }

    public String getTelemetryTopic() {
        return telemetryTopic;
    }

    public void setTelemetryTopic(String telemetryTopic) {
        this.telemetryTopic = telemetryTopic;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public double getMaxSpeedKmh() {
        return maxSpeedKmh;
    }

    public void setMaxSpeedKmh(double maxSpeedKmh) {
        this.maxSpeedKmh = maxSpeedKmh;
    }

    public double getSpeedLimitKmh() {
        return speedLimitKmh;
    }

    public void setSpeedLimitKmh(double speedLimitKmh) {
        this.speedLimitKmh = speedLimitKmh;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    public void setIdleMinutes(int idleMinutes) {
        this.idleMinutes = idleMinutes;
    }

    public double getIdleSpeedKmh() {
        return idleSpeedKmh;
    }

    public void setIdleSpeedKmh(double idleSpeedKmh) {
        this.idleSpeedKmh = idleSpeedKmh;
    }

    public double getBatteryLowPct() {
        return batteryLowPct;
    }

    public void setBatteryLowPct(double batteryLowPct) {
        this.batteryLowPct = batteryLowPct;
    }

    public int getSilenceSeconds() {
        return silenceSeconds;
    }

    public void setSilenceSeconds(int silenceSeconds) {
        this.silenceSeconds = silenceSeconds;
    }

    public int getAlertCooldownMinutes() {
        return alertCooldownMinutes;
    }

    public void setAlertCooldownMinutes(int alertCooldownMinutes) {
        this.alertCooldownMinutes = alertCooldownMinutes;
    }

    public int getBroadcastIntervalMs() {
        return broadcastIntervalMs;
    }

    public void setBroadcastIntervalMs(int broadcastIntervalMs) {
        this.broadcastIntervalMs = broadcastIntervalMs;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getDownsampleAfterHours() {
        return downsampleAfterHours;
    }

    public void setDownsampleAfterHours(int downsampleAfterHours) {
        this.downsampleAfterHours = downsampleAfterHours;
    }

    public int getDownsampleSeconds() {
        return downsampleSeconds;
    }

    public void setDownsampleSeconds(int downsampleSeconds) {
        this.downsampleSeconds = downsampleSeconds;
    }

    public int getGeofenceRefreshSeconds() {
        return geofenceRefreshSeconds;
    }

    public void setGeofenceRefreshSeconds(int geofenceRefreshSeconds) {
        this.geofenceRefreshSeconds = geofenceRefreshSeconds;
    }

    public int getKpiMaxPoints() {
        return kpiMaxPoints;
    }

    public void setKpiMaxPoints(int kpiMaxPoints) {
        this.kpiMaxPoints = kpiMaxPoints;
    }

    public int getTrackMaxPoints() {
        return trackMaxPoints;
    }

    public void setTrackMaxPoints(int trackMaxPoints) {
        this.trackMaxPoints = trackMaxPoints;
    }

    public int getAlertBufferSize() {
        return alertBufferSize;
    }

    public void setAlertBufferSize(int alertBufferSize) {
        this.alertBufferSize = alertBufferSize;
    }
}
