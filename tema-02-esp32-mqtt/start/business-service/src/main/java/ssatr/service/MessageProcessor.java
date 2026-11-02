package ssatr.service;

import java.util.concurrent.BlockingQueue;

/**
 * Preia mesajele de un anumit tip ("telemetry", "alarm" sau "status") din coada
 * inbox, le salvează și actualizează dashboard-ul.
 *
 * Formatul mesajelor (câmpuri separate prin ";"):
 *   telemetry:  deviceId;timestamp;temperatura
 *   alarm:      deviceId;timestamp;temperatura;prag
 *   status:     deviceId;timestamp;text
 */
public class MessageProcessor implements Runnable {

    private final String kind;
    private final BlockingQueue<String> inbox;
    private final CsvStorage storage;
    private final Dashboard dashboard;

    public MessageProcessor(String kind, BlockingQueue<String> inbox, CsvStorage storage, Dashboard dashboard) {
        this.kind = kind;
        this.inbox = inbox;
        this.storage = storage;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String message = inbox.take();

                // 1. Salvare în CSV
                storage.append(kind, message);

                // 2. Parsare și actualizare Dashboard
                String[] parts = message.split(";", -1);
                if (parts.length < 3) {
                    continue; // mesaj invalid, ignorăm fără a opri thread-ul
                }

                String deviceId = parts[0].trim();
                long timestamp = Long.parseLong(parts[1].trim());

                switch (kind) {
                    case "telemetry":
                        double temp = Double.parseDouble(parts[2].trim());
                        dashboard.updateTelemetry(deviceId, timestamp, temp);
                        break;

                    case "alarm":
                        double alarmTemp = Double.parseDouble(parts[2].trim());
                        double threshold = parts.length > 3 ? Double.parseDouble(parts[3].trim()) : 0.0;
                        dashboard.updateAlarm(deviceId, timestamp, alarmTemp);
                        System.out.printf(">>> [ALARMA RECEPTIONATA] Device: %s | Temp: %.2f °C | Prag: %.2f | Timestamp: %d <<<%n",
                                deviceId, alarmTemp, threshold, timestamp);
                        break;

                    case "status":
                        String statusText = parts[2].trim();
                        dashboard.updateStatus(deviceId, timestamp, statusText);
                        System.out.printf("[STATUS CONFIRMATION] Device: %s -> %s%n", deviceId, statusText);
                        break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Erorile de parsare dintr-un mesaj corupt nu trebuie să oprească firul
                System.out.println("[MessageProcessor-" + kind + "] Eroare procesare mesaj: " + e.getMessage());
            }
        }
    }
}