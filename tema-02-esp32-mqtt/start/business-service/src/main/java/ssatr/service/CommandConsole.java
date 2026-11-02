package ssatr.service;

import java.util.Scanner;

/**
 * Consola operatorului: citește comenzi de la tastatură.
 *
 *   show                               afișează dashboard-ul
 *   <deviceId> <COMANDA> [parametru]   trimite o comandă unui device, de exemplu
 *                                      device-1 SET_INTERVAL 1000
 *   help                               afișează comenzile
 *   exit                               oprește serviciul
 */
public class CommandConsole implements Runnable {

    private final MqttConnection mqtt;
    private final Dashboard dashboard;

    public CommandConsole(MqttConnection mqtt, Dashboard dashboard) {
        this.mqtt = mqtt;
        this.dashboard = dashboard;
    }

    @Override
    public void run() {
        Scanner scanner = new Scanner(System.in);
        printHelp();

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            } else if (line.equals("exit")) {
                break;
            } else if (line.equals("help")) {
                printHelp();
            } else if (line.equals("show")) {
                dashboard.print();
            } else {
                sendCommand(line);
            }
        }
    }

    private void sendCommand(String line) {
        // Separa deviceId de restul comenzii (ex: "esp32-a1b2c3 SET_INTERVAL 1000")
        int spaceIndex = line.indexOf(' ');
        if (spaceIndex <= 0 || spaceIndex == line.length() - 1) {
            System.out.println("Eroare format comanda! Format corect: <deviceId> <COMANDA> [argument]");
            System.out.println("Exemplu: esp32-a1b2c3 SET_INTERVAL 1000");
            return;
        }

        String deviceId = line.substring(0, spaceIndex).trim();
        String command = line.substring(spaceIndex + 1).trim();

        String topic = Config.commandTopic(deviceId);
        mqtt.publish(topic, command);
        System.out.printf("[CONSOLE] Comanda \"%s\" trimisa cu succes catre %s (topic: %s)%n", command, deviceId, topic);
    }

    private void printHelp() {
        System.out.println("Comenzi: show | <deviceId> SET_INTERVAL <ms> | <deviceId> SET_THRESHOLD <valoare>");
        System.out.println("         <deviceId> RESET | <deviceId> STATUS | help | exit");
    }
}