package ssatr.device;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * TASK APERIODIC: execută comenzile primite de la business service.
 *
 * Comenzile sosesc oricând, pe MQTT, fără un interval minim între ele, și sunt
 * puse în coada commands. Fiecare comandă eliberează un job al taskului.
 * Comenzi care trebuie suportate:
 *
 *   SET_INTERVAL <ms>       schimbă perioada T a taskului periodic
 *   SET_THRESHOLD <valoare> schimbă pragul de alarmă
 *   RESET                   readuce senzorul la valoarea inițială
 *   STATUS                  raportează perioada și pragul curente
 *
 * După executarea fiecărei comenzi, device-ul publică pe Config.statusTopic(deviceId)
 * o confirmare: "deviceId;timestamp;text", de exemplu
 * "device-1;1727600000000;OK SET_INTERVAL 1000".
 */
public class AperiodicCommandTask implements Runnable {

    private final String deviceId;
    private final TemperatureSensor sensor;
    private final MqttConnection mqtt;
    private final BlockingQueue<String> commands;
    private final PeriodicTelemetryTask periodic;

    public AperiodicCommandTask(String deviceId, TemperatureSensor sensor, MqttConnection mqtt,
                                BlockingQueue<String> commands, PeriodicTelemetryTask periodic) {
        this.deviceId = deviceId;
        this.sensor = sensor;
        this.mqtt = mqtt;
        this.commands = commands;
        this.periodic = periodic;
    }

    @Override
    public void run() {
        // TODO 3: implementați taskul aperiodic:
        //   - preluați comenzile din coadă: commands.take()
        //   - separați numele comenzii de parametru: command.split(" ")
        //   - executați comanda (periodic.setPeriodMs(...), sensor.reset() etc.)
        //   - publicați confirmarea pe Config.statusTopic(deviceId)
        //   - o comandă necunoscută sau un parametru invalid nu trebuie să
        //     oprească taskul: publicați "ERROR <motiv>"
        //
        // Vezi exemplul exemple/01-taskuri/.../AperiodicTaskExample.java
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Așteaptă comanda din coada populată de callback-ul MQTT
                String rawCommand = commands.take();
                if (rawCommand == null || rawCommand.trim().isEmpty()) {
                    continue;
                }

                String cmdLine = rawCommand.trim();
                String[] tokens = cmdLine.split("\\s+");
                String action = tokens[0].toUpperCase(Locale.ROOT);

                String responseText;

                switch (action) {
                    case "SET_INTERVAL":
                        if (tokens.length >= 2) {
                            try {
                                long newInterval = Long.parseLong(tokens[1]);
                                if (newInterval > 0) {
                                    periodic.setPeriodMs(newInterval);
                                    responseText = "OK SET_INTERVAL " + newInterval;
                                } else {
                                    responseText = "ERROR Intervalul trebuie sa fie pozitiv";
                                }
                            } catch (NumberFormatException e) {
                                responseText = "ERROR Parametru numeric invalid pentru SET_INTERVAL";
                            }
                        } else {
                            responseText = "ERROR Lipseste valoarea pentru SET_INTERVAL";
                        }
                        break;

                    case "SET_THRESHOLD":
                        if (tokens.length >= 2) {
                            try {
                                double newThreshold = Double.parseDouble(tokens[1]);
                                periodic.setThreshold(newThreshold);
                                responseText = String.format(Locale.US, "OK SET_THRESHOLD %.2f", newThreshold);
                            } catch (NumberFormatException e) {
                                responseText = "ERROR Parametru numeric invalid pentru SET_THRESHOLD";
                            }
                        } else {
                            responseText = "ERROR Lipseste valoarea pentru SET_THRESHOLD";
                        }
                        break;

                    case "RESET":
                        sensor.reset();
                        responseText = "OK RESET";
                        break;

                    case "STATUS":
                        responseText = String.format(Locale.US, "OK STATUS period=%d threshold=%.2f",
                                periodic.getPeriodMs(), periodic.getThreshold());
                        break;

                    default:
                        responseText = "ERROR Comanda necunoscuta: " + action;
                        break;
                }

                // Publicare confirmare pe topicul de status
                long now = System.currentTimeMillis();
                String statusPayload = String.format(Locale.US, "%s;%d;%s", deviceId, now, responseText);
                mqtt.publish(Config.statusTopic(deviceId), statusPayload);
                System.out.println("[APERIODIC][" + deviceId + "] " + responseText);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[" + deviceId + "] Eroare in taskul aperiodic: " + e.getMessage());
            }
        }
    }
}