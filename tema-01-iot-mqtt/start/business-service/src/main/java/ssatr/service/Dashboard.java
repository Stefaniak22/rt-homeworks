package ssatr.service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ultima stare cunoscută a fiecărui device: ultima temperatură, momentul
 * ultimului mesaj, numărul de alarme etc.
 *
 * Este actualizată de thread-urile MessageProcessor și citită de CommandConsole,
 * deci accesul trebuie sincronizat.
 */
public class Dashboard {

    // TODO 5: alegeți structura de date pentru starea device-urilor
    //   (de exemplu un Map<String, ...> cu cheia deviceId)
    //   și adăugați metodele de actualizare apelate din MessageProcessor.

    public static class DeviceStatus {
        private final String deviceId;
        private double lastTemperature;
        private long lastMessageTimestamp;
        private int alarmCount;

        public DeviceStatus(String deviceId) {
            this.deviceId = deviceId;
            this.lastTemperature = Double.NaN;
            this.lastMessageTimestamp = System.currentTimeMillis();
            this.alarmCount = 0;
        }

        public synchronized void updateTelemetry(double temp, long timestamp) {
            this.lastTemperature = temp;
            this.lastMessageTimestamp = timestamp;
        }

        public synchronized void registerAlarm(long timestamp) {
            this.alarmCount++;
            this.lastMessageTimestamp = timestamp;
        }

        public synchronized void updateTimestamp(long timestamp) {
            this.lastMessageTimestamp = timestamp;
        }

        public synchronized String getFormattedRow() {
            long ageSec = Math.max(0, (System.currentTimeMillis() - lastMessageTimestamp) / 1000);
            String tempStr = Double.isNaN(lastTemperature) ? "-" : String.format(Locale.US, "%.2f °C", lastTemperature);
            return String.format("%-15s %-12s %-16s %-8d", deviceId, tempStr, "acum " + ageSec + " s", alarmCount);
        }
    }

    private final Map<String, DeviceStatus> devices = new ConcurrentHashMap<>();

    private DeviceStatus getOrCreate(String deviceId) {
        return devices.computeIfAbsent(deviceId, DeviceStatus::new);
    }

    public void updateTelemetry(String deviceId, double temp, long timestamp) {
        getOrCreate(deviceId).updateTelemetry(temp, timestamp);
    }

    public void registerAlarm(String deviceId, long timestamp) {
        getOrCreate(deviceId).registerAlarm(timestamp);
    }

    public void updateStatusReceived(String deviceId, long timestamp) {
        getOrCreate(deviceId).updateTimestamp(timestamp);
    }

    /** Afișează un tabel cu starea tuturor device-urilor. */
    public void print() {
        // TODO 5: afișați câte un rând pentru fiecare device, de exemplu:
        //   DEVICE     TEMP    ULTIMUL MESAJ   ALARME
        //   device-1   23.41   acum 1 s        2
        if (devices.isEmpty()) {
            System.out.println("\n[DASHBOARD] Niciun device conectat/înregistrat încă.");
            return;
        }

        System.out.println("\n=========================== DASHBOARD ===========================");
        System.out.printf("%-15s %-12s %-16s %-8s%n", "DEVICE", "TEMP", "ULTIMUL MESAJ", "ALARME");
        System.out.println("-----------------------------------------------------------------");
        for (DeviceStatus ds : devices.values()) {
            System.out.println(ds.getFormattedRow());
        }
        System.out.println("=================================================================\n");
    }
}
