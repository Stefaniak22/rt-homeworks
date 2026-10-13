package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK SPORADIC: tratează alarmele de temperatură.
 *
 * Evenimentele sosesc în coada alarmEvents (puse de taskul periodic).
 * Intervalul minim între sosiri este MIN_INTERARRIVAL_MS: între două eliberări
 * ale taskului (două alarme publicate) trebuie să treacă cel puțin atât.
 * Intervalul este impus de cod: evenimentele care sosesc mai des sunt
 * respinse, ca să nu fie inundat brokerul.
 */
public class SporadicAlarmTask implements Runnable {

    static final long MIN_INTERARRIVAL_MS = 5000;

    private final String deviceId;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    private final PeriodicTelemetryTask periodic;

    public SporadicAlarmTask(String deviceId, MqttConnection mqtt, BlockingQueue<Double> alarmEvents, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        // TODO 2: implementați taskul sporadic:
        //   - așteptați un eveniment: alarmEvents.take()
        //   - dacă de la ultima eliberare au trecut mai puțin de
        //     MIN_INTERARRIVAL_MS, respingeți evenimentul (afișați un mesaj)
        //   - altfel publicați pe Config.alarmTopic(deviceId) mesajul
        //     "deviceId;timestamp;temperatura;prag"
        //     (pragul curent poate fi transmis împreună cu temperatura sau
        //      citit din PeriodicTelemetryTask; alegeți și justificați în raport)
        //
        // Vezi exemplul exemple/01-taskuri/.../SporadicTaskExample.java

        long lastReleaseTime = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Așteptăm un eveniment din coadă (apel blocant)
                Double temp = alarmEvents.take();
                long now = System.currentTimeMillis();

                // Verificare interval minim între sosiri (minimum inter-arrival time)
                long sinceLast = now - lastReleaseTime;
                if (sinceLast < MIN_INTERARRIVAL_MS) {
                    System.out.printf(Locale.US,
                            "   [SPORADIC][%s] Alarma RESPINSĂ: au trecut doar %d ms (interval minim %d ms)%n",
                            deviceId, sinceLast, MIN_INTERARRIVAL_MS);
                    continue;
                }

                // Eveniment acceptat: actualizăm momentul ultimei eliberări
                lastReleaseTime = now;

                // Citim pragul curent din taskul periodic (care poate fi modificat aperiodic)
                double currentThreshold = periodic.getThreshold();

                // Format cerut: "deviceId;timestamp;temperatura;prag"
                String payload = String.format(Locale.US, "%s;%d;%.2f;%.2f",
                        deviceId, now, temp, currentThreshold);

                mqtt.publish(Config.alarmTopic(deviceId), payload);
                System.out.printf(Locale.US,
                        "   [SPORADIC][%s] Alarma publicată: temp=%.2f, prag=%.2f%n",
                        deviceId, temp, currentThreshold);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[" + deviceId + "] Eroare in taskul sporadic: " + e.getMessage());
            }
        }
    }
}
