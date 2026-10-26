
#define BUTTON_PIN 33

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  Serial.println("NEXUS ESP32 #1");
  Serial.println("Pushbutton Test");
}

void loop() {
  if (digitalRead(BUTTON_PIN) == LOW) {
    Serial.println("BUTTON PRESSED");
  } else {
    Serial.println("BUTTON RELEASED");
  }

  delay(500);
}
