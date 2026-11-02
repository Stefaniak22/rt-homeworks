package ssatr.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul este sincronizat.
 */
public class Dashboard {

    public static class DeviceState {
        public String deviceId;
        public double lastTemperature = Double.NaN;
        public long lastSeenTimestamp = 0;
        public int alarmCount = 0;
        public String lastStatus = "-";

        public DeviceState(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    private final Map<String, DeviceState> devices = new ConcurrentHashMap<>();

    private DeviceState getOrCreate(String deviceId) {
        return devices.computeIfAbsent(deviceId, DeviceState::new);
    }

    public synchronized void updateTelemetry(String deviceId, long timestamp, double temp) {
        DeviceState state = getOrCreate(deviceId);
        state.lastTemperature = temp;
        state.lastSeenTimestamp = Math.max(state.lastSeenTimestamp, timestamp);
    }

    public synchronized void updateAlarm(String deviceId, long timestamp, double temp) {
        DeviceState state = getOrCreate(deviceId);
        state.lastTemperature = temp;
        state.alarmCount++;
        state.lastSeenTimestamp = Math.max(state.lastSeenTimestamp, timestamp);
    }

    public synchronized void updateStatus(String deviceId, long timestamp, String statusText) {
        DeviceState state = getOrCreate(deviceId);
        state.lastStatus = statusText;
        state.lastSeenTimestamp = Math.max(state.lastSeenTimestamp, timestamp);
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public synchronized void print() {
        if (devices.isEmpty()) {
            System.out.println("Niciun dispozitiv conectat pana in acest moment.");
            return;
        }

        System.out.println("--------------------------------------------------------------------------------------");
        System.out.printf("%-18s | %-10s | %-18s | %-8s | %s%n", "DEVICE", "TEMP (°C)", "ULTIMUL MESAJ", "ALARME", "STATUS");
        System.out.println("--------------------------------------------------------------------------------------");

        long now = System.currentTimeMillis();
        for (DeviceState state : devices.values()) {
            String tempStr = Double.isNaN(state.lastTemperature) ? "-" : String.format("%.2f", state.lastTemperature);

            String seenStr = "-";
            if (state.lastSeenTimestamp > 0) {
                long diffSec = Math.max(0, (now - state.lastSeenTimestamp) / 1000);
                seenStr = "acum " + diffSec + " s";
            }

            System.out.printf("%-18s | %-10s | %-18s | %-8d | %s%n",
                    state.deviceId, tempStr, seenStr, state.alarmCount, state.lastStatus);
        }
        System.out.println("--------------------------------------------------------------------------------------");
    }
}