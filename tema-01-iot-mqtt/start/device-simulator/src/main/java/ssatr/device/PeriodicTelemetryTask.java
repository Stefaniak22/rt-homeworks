package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TASK PERIODIC, cu perioada T = periodMs: fiecare job citește senzorul și
 * publică valoarea pe topic-ul de telemetrie.
 *
 * Dacă temperatura depășește pragul, taskul NU tratează el alarma, ci doar
 * semnalează un eveniment în coada alarmEvents. Alarma este tratată de
 * taskul sporadic.
 */
public class PeriodicTelemetryTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<Double> alarmEvents;

    // volatile: sunt citite aici, dar modificate de taskul aperiodic (alt thread)
    private volatile long periodMs = 2000;
    private volatile double threshold = 28.0;

    public PeriodicTelemetryTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                 BlockingQueue<Double> alarmEvents) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.alarmEvents = alarmEvents;
    }

    @Override
    public void run() {
        // TODO 1: implementați bucla taskului periodic. Fiecare job:
        //   - citiți temperatura: sensor.read()
        //   - publicați pe Config.telemetryTopic(deviceId) mesajul "deviceId;timestamp;temperatura"
        //     (timestamp = System.currentTimeMillis(); temperatura cu punct zecimal,
        //      de ex. String.format(Locale.US, "%.2f", t), ca să poată fi citită
        //      cu Double.parseDouble în business-service)
        //   - dacă temperatura > threshold, puneți valoarea în coada alarmEvents
        //
        // Folosiți așteptare absolută, nu relativă: Thread.sleep(periodMs) după
        // fiecare job duce la derivă (perioada reală devine T + C + întârzieri).
        // Calculați eliberarea următorului job pe grilă (next = next + periodMs)
        // și așteptați doar cât a mai rămas până la ea. Pentru grilă folosiți
        // un timp monoton: System.nanoTime().
        //
        // periodMs se poate schimba în timpul rulării (comanda SET_INTERVAL),
        // deci citiți-l la fiecare job.


        // Punctul de start pe grilă (timp monoton)
        long nextReleaseNano = System.nanoTime();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Execuție job: citire senzor
                double temp = sensor.read();
                long nowWallClock = System.currentTimeMillis();

                // 2. Publicare telemetrie pe MQTT: "deviceId;timestamp;temperatura"
                String payload = String.format(Locale.US, "%s;%d;%.2f", deviceId, nowWallClock, temp);
                mqtt.publish(Config.telemetryTopic(deviceId), payload);

                // 3. Verificare depășire prag
                if (temp > threshold) {
                    // Punem valoarea temperaturii în coada de alarme
                    alarmEvents.offer(temp);
                }

                // 4. Avansare pe grilă: r_(k+1) = r_k + T
                // periodMs se citește la fiecare job deoarece se poate schimba prin comanda SET_INTERVAL
                long currentPeriodNano = TimeUnit.MILLISECONDS.toNanos(periodMs);
                nextReleaseNano += currentPeriodNano;

                // 5. Așteptare absolută
                long sleepTimeNano = nextReleaseNano - System.nanoTime();

                if (sleepTimeNano > 0) {
                    TimeUnit.NANOSECONDS.sleep(sleepTimeNano);
                } else {
                    // Overrun (depășire de perioadă): jobul curent a durat mai mult decât T.
                    // Nu dormim deloc, jobul următor pornește imediat pentru a recupera grila.
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[" + deviceId + "] Eroare in taskul periodic: " + e.getMessage());
            }
        }
    }

    public void setPeriodMs(long periodMs) {
        this.periodMs = periodMs;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getPeriodMs() {
        return periodMs;
    }

    public double getThreshold() {
        return threshold;
    }
}
