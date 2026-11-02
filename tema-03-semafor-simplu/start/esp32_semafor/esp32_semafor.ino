// Semafor pentru pietoni, cu buton, pe ESP32-C3 Super Mini.
//
// Placa este controlerul semaforului; lămpile sunt desenate de dashboard, care
// le aprinde după mesajele "lights" publicate de placă. Patru activități:
//
//   ISR buton BOOT ----+
//                      +--(coada butonului)--> task sporadic
//   command_received --+   (BUTTON)                 |
//                                                   v
//                                         [ starea semaforului ] <-- mutex
//                                            ^          |
//                                            |          v
//                         task periodic de control   task periodic de ieșire
//                         (fazele, T = 100 ms)       (lămpile, T = 500 ms) --> lights

#include "config.h"
#include "connection.h"

// Fazele semaforului (vezi tabelul din cerință).
enum Phase { CAR_GREEN, CAR_YELLOW, ALL_RED, PED_GREEN };
const char* const PHASE_NAMES[] = {"CAR_GREEN", "CAR_YELLOW", "ALL_RED", "PED_GREEN"};

// Culorile unei lămpi, cu numele din mesajul "lights".
enum Light { RED, YELLOW, GREEN };
const char* const LIGHT_NAMES[] = {"RED", "YELLOW", "GREEN"};

// TODO 0 (c): Starea semaforului și mecanismele FreeRTOS
struct TrafficLightState {
    Phase phase;
    uint32_t phase_start_ms; // millis() la intrarea în faza curentă
    bool pedestrian_request; // cererea pietonului (lampa AȘTEPTAȚI)
};

static TrafficLightState g_state = {CAR_GREEN, 0, false};
static SemaphoreHandle_t g_state_mutex = NULL;

// Elementul din coada butonului
struct ButtonPress {
    uint32_t at_ms;
    bool from_board; // true = BOOT (ISR), false = comanda MQTT
};

static QueueHandle_t g_button_queue = NULL;

// TODO 3: ISR pentru butonul BOOT (GPIO9, activ pe LOW)
void IRAM_ATTR button_isr(void) {
    ButtonPress press = {
        (uint32_t)(xTaskGetTickCountFromISR() * portTICK_PERIOD_MS),
        true
    };
    BaseType_t woken = pdFALSE;
    xQueueSendFromISR(g_button_queue, &press, &woken);
    portYIELD_FROM_ISR(woken);
}

// TODO 3: Recepția comenzii "BUTTON" prin MQTT
void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);
    if (strcmp(text, "BUTTON") == 0) {
        ButtonPress press = {millis(), false};
        if (xQueueSend(g_button_queue, &press, 0) != pdTRUE) {
            Serial.println("[mqtt] coada butonului este plină, apăsare pierdută");
        }
    }
}

// TODO 1: TASK PERIODIC DE CONTROL (T = 100 ms)
static void control_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();

    for (;;) {
        uint32_t now = millis();
        Phase before;
        Phase next;

        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        before = g_state.phase;
        next = before;
        uint32_t elapsed = now - g_state.phase_start_ms;

        switch (before) {
            case CAR_GREEN:
                if (elapsed >= CAR_GREEN_MIN_MS && g_state.pedestrian_request) {
                    next = CAR_YELLOW;
                }
                break;
            case CAR_YELLOW:
                if (elapsed >= CAR_YELLOW_MS) {
                    next = ALL_RED;
                }
                break;
            case ALL_RED:
                if (elapsed >= ALL_RED_MS) {
                    next = PED_GREEN;
                }
                break;
            case PED_GREEN:
                if (elapsed >= PED_GREEN_MS) {
                    next = CAR_GREEN;
                }
                break;
        }

        if (next != before) {
            g_state.phase = next;
            g_state.phase_start_ms = now;
            if (next == PED_GREEN) {
                g_state.pedestrian_request = false; // pietonul primește verde, cererea se șterge
            }
        }
        xSemaphoreGive(g_state_mutex);

        if (next != before) {
            Serial.printf("[control] %s -> %s\n", PHASE_NAMES[before], PHASE_NAMES[next]);
        }

        xTaskDelayUntil(&last, pdMS_TO_TICKS(CONTROL_PERIOD_MS));
    }
}

// TODO 2: TASK PERIODIC DE IEȘIRE (T = 500 ms)
static void output_task(void* arg) {
    (void)arg;
    TickType_t last = xTaskGetTickCount();

    for (;;) {
        uint32_t now = millis();
        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        TrafficLightState s = g_state; // copiem starea sub mutex
        xSemaphoreGive(g_state_mutex);

        // Stabilim culorile lămpilor conform fazei curente
        Light car_light = RED;
        Light ped_light = RED;
        switch (s.phase) {
            case CAR_GREEN:
                car_light = GREEN;
                ped_light = RED;
                break;
            case CAR_YELLOW:
                car_light = YELLOW;
                ped_light = RED;
                break;
            case ALL_RED:
                car_light = RED;
                ped_light = RED;
                break;
            case PED_GREEN:
                car_light = RED;
                ped_light = GREEN;
                break;
        }

        // Calculează numărătoarea inversă pentru pietoni (rotunjită în sus)
        uint32_t seconds = 0;
        if (s.phase == PED_GREEN) {
            uint32_t elapsed = min(now - s.phase_start_ms, (uint32_t)PED_GREEN_MS);
            seconds = (PED_GREEN_MS - elapsed + 999) / 1000;
        }

        // Aprinde/stinge LED-ul fizic (LED_PIN aprins pe LOW când AȘTEPTAȚI e activ)
        digitalWrite(LED_PIN, s.pedestrian_request ? LOW : HIGH);

        // Publică mesajul pe topicul lights:
        // Format: deviceId;timestamp;faza;masini;pietoni;asteptati;secunde
        char payload[128];
        snprintf(payload, sizeof(payload), "%s;%lld;%s;%s;%s;%d;%lu",
                 DEVICE_ID,
                 (long long)epoch_ms(),
                 PHASE_NAMES[s.phase],
                 LIGHT_NAMES[car_light],
                 LIGHT_NAMES[ped_light],
                 s.pedestrian_request ? 1 : 0,
                 (unsigned long)seconds);

        mqtt_publish("lights", payload);

        xTaskDelayUntil(&last, pdMS_TO_TICKS(OUTPUT_PERIOD_MS));
    }
}

// TODO 3: TASK SPORADIC PENTRU BUTON
static void button_task(void* arg) {
    (void)arg;
    bool any = false;
    uint32_t last_accepted_ms = 0;

    for (;;) {
        ButtonPress press;
        xQueueReceive(g_button_queue, &press, portMAX_DELAY);

        const char* src = press.from_board ? "BOOT" : "MQTT";
        uint32_t delta = press.at_ms - last_accepted_ms;

        if (any && delta < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] %-4s t = %6lu ms  RESPINSĂ: la %lu ms după cea acceptată\n",
                          src, (unsigned long)press.at_ms, (unsigned long)delta);
            continue;
        }

        any = true;
        last_accepted_ms = press.at_ms;

        xSemaphoreTake(g_state_mutex, portMAX_DELAY);
        g_state.pedestrian_request = true;
        xSemaphoreGive(g_state_mutex);

        Serial.printf("[sporadic] %-4s t = %6lu ms  ACCEPTATĂ (cerere înregistrată)\n",
                      src, (unsigned long)press.at_ms);
    }
}

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 03: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);  // LED stins
    pinMode(BUTTON_PIN, INPUT_PULLUP);

    wifi_connect();
    time_sync();

    // TODO 0 (a): Creare Mutex și Coadă înainte de mqtt_start()
    g_state_mutex = xSemaphoreCreateMutex();
    g_button_queue = xQueueCreate(10, sizeof(ButtonPress));

    xSemaphoreTake(g_state_mutex, portMAX_DELAY);
    g_state.phase = CAR_GREEN;
    g_state.phase_start_ms = millis();
    g_state.pedestrian_request = false;
    xSemaphoreGive(g_state_mutex);

    mqtt_start();

    // TODO 0 (b): Pornire taskuri (Priorități RM: Sporadic > Control > Output)
    xTaskCreate(button_task, "sporadic_btn", 4096, NULL, 5, NULL);
    xTaskCreate(control_task,  "control",      4096, NULL, 4, NULL);
    xTaskCreate(output_task,   "output",       4096, NULL, 3, NULL);

    // Înregistrare întrerupere buton BOOT (GPIO9 pe FALLING)
    attachInterrupt(digitalPinToInterrupt(BUTTON_PIN), button_isr, FALLING);
}

void loop() {
    delay(1000);
}