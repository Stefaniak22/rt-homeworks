package ssatr.service;

import java.util.Locale;
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
        // TODO 4: într-o buclă:
        //   - preluați un mesaj: inbox.take()
        //   - salvați mesajul în fișierul CSV: storage.append(kind, mesaj)
        //     (CsvStorage este gata implementată)
        //   - actualizați dashboard-ul, în funcție de kind
        //   - alarmele și confirmările se afișează imediat în consolă
        //   - un mesaj greșit (format invalid) nu trebuie să oprească thread-ul
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Preluare mesaj din coadă (blocant)
                String message = inbox.take();
                if (message == null || message.trim().isEmpty()) {
                    continue;
                }

                // 2. Salvare în CSV corespunzător (data/<kind>.csv)
                try {
                    storage.append(kind, message);
                } catch (Exception e) {
                    System.err.println("[CSV][" + kind + "] Eroare la scrierea in fisier: " + e.getMessage());
                }

                // 3. Parsare mesaj robustă (fără a opri thread-ul în caz de eroare de format)
                String[] parts = message.split(";");
                if (parts.length < 3) {
                    System.err.println("[WARN][" + kind + "] Format invalid de mesaj primit: " + message);
                    continue;
                }

                String deviceId = parts[0].trim();
                long timestamp;
                try {
                    timestamp = Long.parseLong(parts[1].trim());
                } catch (NumberFormatException e) {
                    timestamp = System.currentTimeMillis();
                }

                switch (kind) {
                    case "telemetry":
                        try {
                            double temp = Double.parseDouble(parts[2].trim());
                            dashboard.updateTelemetry(deviceId, temp, timestamp);
                        } catch (NumberFormatException e) {
                            System.err.println("[TELEMETRY] Eroare conversie temperatura: " + parts[2]);
                        }
                        break;

                    case "alarm":
                        dashboard.registerAlarm(deviceId, timestamp);
                        double alarmTemp = Double.NaN;
                        double threshold = Double.NaN;
                        try {
                            alarmTemp = Double.parseDouble(parts[2].trim());
                            if (parts.length >= 4) {
                                threshold = Double.parseDouble(parts[3].trim());
                            }
                        } catch (NumberFormatException ignored) {}

                        // Afișare imediată în consolă pentru operator
                        System.out.printf(Locale.US,
                                "%n>>> [ALARMA RECEPTIONATA] Device: %s | Temp: %.2f °C | Prag: %.2f | Timestamp: %d <<<%n%n",
                                deviceId, alarmTemp, threshold, timestamp);
                        break;

                    case "status":
                        dashboard.updateStatusReceived(deviceId, timestamp);
                        String statusText = parts[2].trim();

                        // Afișare imediată în consolă pentru operator
                        System.out.printf("%n[STATUS CONFIRMATION] Device: %s -> %s%n%n", deviceId, statusText);
                        break;

                    default:
                        System.err.println("[UNKNOWN KIND] Tip mesaj necunoscut: " + kind);
                        break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[ERROR][" + kind + "] Exceptie neprevazuta: " + e.getMessage());
            }
        }
    }
}