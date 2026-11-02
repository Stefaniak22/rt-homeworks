// Device-ul IoT din tema 01, pe ESP32-C3 Super Mini cu FreeRTOS.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

// Structuri pentru cozile FreeRTOS (transmise prin valoare, nu pointeri)
struct AlarmEvent {
    float temperature;
    float threshold;
};

struct CommandMessage {
    char text[COMMAND_MAX_LEN];
};

// Cozile partajate între taskuri
static QueueHandle_t alarm_queue = NULL;
static QueueHandle_t command_queue = NULL;

// Parametri globali volatili (partajați și modificați concurent din taskuri)
static volatile uint32_t period_ms = PERIOD_MS_INITIAL;
static volatile float current_threshold = THRESHOLD_INITIAL;

// Temperatura cipului intern ESP32-C3
float read_temperature(void) {
    return temperatureRead();
}

// =============================================================================
// TODO 1: TASK PERIODIC
// =============================================================================
static void task_periodic(void *pvParameters) {
    TickType_t last_wake_time = xTaskGetTickCount();

    for (;;) {
        uint32_t t_ms = period_ms;
        // Așteptare absolută pe grilă r_k = r_0 + k*T (fără derivă de timp)
        xTaskDelayUntil(&last_wake_time, pdMS_TO_TICKS(t_ms));

        float temp = read_temperature();
        int64_t now = epoch_ms();

        // 1. Publică telemetria: "deviceId;timestamp;temperatura"
        char telemetry_payload[128];
        snprintf(telemetry_payload, sizeof(telemetry_payload), "%s;%lld;%.2f",
                 DEVICE_ID, (long long)now, temp);
        mqtt_publish("telemetry", telemetry_payload);
        Serial.printf("[PERIODIC] Telemetrie publicată: %s\n", telemetry_payload);

        // 2. Verificare prag: dacă temp > prag, pune evenimentul în coada de alarme
        float thr = current_threshold;
        if (temp > thr) {
            AlarmEvent ev;
            ev.temperature = temp;
            ev.threshold = thr;

            // Timeout 0: nu blochează niciodată taskul periodic
            if (xQueueSend(alarm_queue, &ev, 0) != pdPASS) {
                Serial.println("[PERIODIC] Coada de alarme este plină! Eveniment ignorat.");
            }
        }
    }
}

// =============================================================================
// TODO 2: TASK SPORADIC
// =============================================================================
static void task_sporadic(void *pvParameters) {
    AlarmEvent ev;
    uint32_t last_alarm_ms = 0;
    bool is_first_alarm = true;

    for (;;) {
        // Așteaptă blocant un eveniment din coada de alarme
        if (xQueueReceive(alarm_queue, &ev, portMAX_DELAY) == pdPASS) {
            uint32_t now_ms = millis(); // timp monoton pentru calculul intervalelor

            // Verifică intervalul minim între sosiri MIN_INTERARRIVAL_MS (5000 ms)
            if (is_first_alarm || (now_ms - last_alarm_ms >= MIN_INTERARRIVAL_MS)) {
                last_alarm_ms = now_ms;
                is_first_alarm = false;

                int64_t now = epoch_ms();
                char alarm_payload[128];
                snprintf(alarm_payload, sizeof(alarm_payload), "%s;%lld;%.2f;%.2f",
                         DEVICE_ID, (long long)now, ev.temperature, ev.threshold);
                mqtt_publish("alarm", alarm_payload);
                Serial.printf("[SPORADIC] Alarmă publicată: %s\n", alarm_payload);
            } else {
                Serial.printf("[SPORADIC] Alarmă RESPINSĂ: au trecut doar %lu ms (interval minim %lu ms)\n",
                              (unsigned long)(now_ms - last_alarm_ms), (unsigned long)MIN_INTERARRIVAL_MS);
            }
        }
    }
}

// =============================================================================
// TODO 3: TASK APERIODIC & RECEPTIE COMENZI
// =============================================================================
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    if (!text || command_queue == NULL) return;

    CommandMessage msg;
    strncpy(msg.text, text, sizeof(msg.text) - 1);
    msg.text[sizeof(msg.text) - 1] = '\0';

    // Se trimite în coadă fără blocare (timeout 0)
    xQueueSend(command_queue, &msg, 0);
}

static void task_aperiodic(void *pvParameters) {
    CommandMessage cmd;

    for (;;) {
        // Așteaptă blocant comenzi din coadă
        if (xQueueReceive(command_queue, &cmd, portMAX_DELAY) == pdPASS) {
            char response_msg[128] = "";
            char arg[32] = "";

            if (sscanf(cmd.text, "SET_INTERVAL %s", arg) == 1) {
                long val = atol(arg);
                if (val > 0) {
                    period_ms = (uint32_t)val;
                    snprintf(response_msg, sizeof(response_msg), "OK SET_INTERVAL %lu", (unsigned long)period_ms);
                } else {
                    snprintf(response_msg, sizeof(response_msg), "ERROR interval invalid: %s", arg);
                }
            } else if (sscanf(cmd.text, "SET_THRESHOLD %s", arg) == 1) {
                float val = atof(arg);
                current_threshold = val;
                snprintf(response_msg, sizeof(response_msg), "OK SET_THRESHOLD %.2f", current_threshold);
            } else if (strcmp(cmd.text, "RESET") == 0) {
                period_ms = PERIOD_MS_INITIAL;
                current_threshold = THRESHOLD_INITIAL;
                snprintf(response_msg, sizeof(response_msg), "OK RESET");
            } else if (strcmp(cmd.text, "STATUS") == 0) {
                snprintf(response_msg, sizeof(response_msg), "OK STATUS period=%lu threshold=%.2f",
                         (unsigned long)period_ms, current_threshold);
            } else {
                snprintf(response_msg, sizeof(response_msg), "ERROR comanda necunoscuta: %s", cmd.text);
            }

            // Publicare confirmare pe topicul status: "deviceId;timestamp;text"
            int64_t now = epoch_ms();
            char status_payload[192];
            snprintf(status_payload, sizeof(status_payload), "%s;%lld;%s",
                     DEVICE_ID, (long long)now, response_msg);
            mqtt_publish("status", status_payload);
            Serial.printf("[APERIODIC] Status publicat: %s\n", status_payload);
        }
    }
}

// =============================================================================
// SETUP & LOOP
// =============================================================================
void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    // TODO 0 (a): Cozile se creează ÎNAINTE de mqtt_start()
    alarm_queue = xQueueCreate(10, sizeof(AlarmEvent));
    command_queue = xQueueCreate(10, sizeof(CommandMessage));

    // Pornire client MQTT (se abonează la comenzi și începe să apeleze command_received)
    mqtt_start();

    // TODO 0 (b): Pornire taskuri DUPĂ mqtt_start()
    // Priorități alese între 2 și 19:
    // periodic (5) > sporadic (4) > aperiodic (3) > loopTask (1)
    // Stivă alocată: 4096 octeți pentru fiecare task
    xTaskCreate(task_periodic,  "task_periodic",  4096, NULL, 5, NULL);
    xTaskCreate(task_sporadic,  "task_sporadic",  4096, NULL, 4, NULL);
    xTaskCreate(task_aperiodic, "task_aperiodic", 4096, NULL, 3, NULL);
}

void loop() {
    // Totul este gestionat de taskurile FreeRTOS
    delay(1000);
}