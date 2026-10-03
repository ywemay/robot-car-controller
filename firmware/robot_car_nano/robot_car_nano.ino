/*
 * robot_car_nano.ino — reference firmware for the Android Robot Car Controller.
 *
 * Board : Arduino Nano (ATmega328P, old bootloader or new — either works)
 * Link  : 115200 baud, 8 data bits, 1 stop bit, no parity  (matches the app)
 *
 * Wire protocol (every frame ends with a single LF, '\n'):
 *
 *     D,<DIR>,<SPEED>\n      DIR   := F | B | L | R | S
 *                            SPEED := 0..255   (PWM duty)
 *
 *     C,<PAN>,<TILT>\n       PAN   := 0..180   (degrees)
 *                            TILT  := 0..180   (degrees)
 *
 * Replies with a short ACK line per frame so the app's Serial Debug Log shows
 * traffic in BOTH directions, e.g.  "ACK D F 180".
 *
 * ---------------------------------------------------------------------------
 * IMPORTANT — the Servo library owns Timer1 on the ATmega328P, which kills
 * analogWrite() on digital pins 9 and 10. The two motor-enable pins below are
 * therefore on Timer0 (5, 6). Pins 9/10 are still fine as plain digitalWrite
 * outputs for the direction pins. Do NOT move ENA/ENB to 9/10 or PWM silently
 * stops working.
 * ---------------------------------------------------------------------------
 *
 * H-Bridge (L298N / TB6612 / DRV8833) + 2 hobby servos.
 * Motors are stopped automatically if no drive frame arrives within FAILSAFE_MS
 * — an important safety net for a moving vehicle on a flaky USB cable.
 */

#include <Servo.h>

// ------------------------------- pin map ----------------------------------
const uint8_t PIN_ENA = 5;    // left  motor PWM   (Timer0)
const uint8_t PIN_IN1 = 7;
const uint8_t PIN_IN2 = 8;
const uint8_t PIN_ENB = 6;    // right motor PWM   (Timer0)
const uint8_t PIN_IN3 = 9;    // plain digital output — see Timer1 note above
const uint8_t PIN_IN4 = 10;

const uint8_t PIN_PAN  = 2;   // pan  servo signal
const uint8_t PIN_TILT = 4;   // tilt servo signal

// ------------------------------- tunables ---------------------------------
const unsigned long FAILSAFE_MS = 1000;   // stop motors after this with no drive frame
const int SERVO_MIN_US = 500;             // tune to your servos
const int SERVO_MAX_US = 2400;

Servo panServo;
Servo tiltServo;

unsigned long lastDriveMs = 0;
char rxBuf[32];
uint8_t rxLen = 0;

// --------------------------- forward declarations --------------------------
// The Arduino IDE silently generates these from the .ino; declaring them
// explicitly keeps the sketch compilable by a plain avr-g++ / arduino-cli too.
void handleFrame(char *frame);
void drive(char dir, int speed);
void setMotor(int left, int right);
void stopMotors();
void ack(const char *verb);

// ------------------------------- setup ------------------------------------
void setup() {
  pinMode(PIN_ENA, OUTPUT); pinMode(PIN_IN1, OUTPUT); pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_ENB, OUTPUT); pinMode(PIN_IN3, OUTPUT); pinMode(PIN_IN4, OUTPUT);
  stopMotors();

  panServo.attach(PIN_PAN, SERVO_MIN_US, SERVO_MAX_US);
  tiltServo.attach(PIN_TILT, SERVO_MIN_US, SERVO_MAX_US);
  panServo.write(90);
  tiltServo.write(90);

  Serial.begin(115200);
  Serial.println(F("READY robot-car 115200 8N1"));
}

// ------------------------------- main loop --------------------------------
void loop() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();

    if (c == '\n') {                     // frame terminator
      rxBuf[rxLen] = '\0';
      if (rxLen > 0) handleFrame(rxBuf);
      rxLen = 0;
    } else if (c != '\r') {
      if (rxLen < sizeof(rxBuf) - 1) {
        rxBuf[rxLen++] = c;
      } else {
        rxLen = 0;                       // oversized garbage — drop it
      }
    }
  }

  // Failsafe: no drive frame recently -> cut the motors.
  if (lastDriveMs != 0 && millis() - lastDriveMs > FAILSAFE_MS) {
    stopMotors();
    lastDriveMs = 0;
    Serial.println(F("ACK D S 0 failsafe"));
  }
}

// ------------------------------- parsing ----------------------------------
// Frame grammar:  <verb>,<arg1>[,<arg2>]
void handleFrame(char *frame) {
  char *verb = strtok(frame, ",");
  if (verb == NULL) return;

  if (verb[0] == 'D' || verb[0] == 'd') {
    char *dir   = strtok(NULL, ",");
    char *speed = strtok(NULL, ",");
    if (dir == NULL || speed == NULL) return;

    int pwm = constrain(atoi(speed), 0, 255);
    drive(dir[0], pwm);
    // Only arm the failsafe for a *moving* command; an explicit stop disarms it.
    lastDriveMs = (dir[0] == 'S' || dir[0] == 's') ? 0 : millis();
    ack("D"); Serial.print(dir[0]); Serial.print(' '); Serial.println(pwm);

  } else if (verb[0] == 'C' || verb[0] == 'c') {
    char *pan  = strtok(NULL, ",");
    char *tilt = strtok(NULL, ",");
    if (pan == NULL || tilt == NULL) return;

    int p = constrain(atoi(pan),  0, 180);
    int t = constrain(atoi(tilt), 0, 180);
    panServo.write(p);
    tiltServo.write(t);
    ack("C"); Serial.print(p); Serial.print(' '); Serial.println(t);
  }
}

// ------------------------------- actuation --------------------------------
void drive(char dir, int speed) {
  switch (dir) {
    case 'F': case 'f': setMotor( speed,  speed); break;
    case 'B': case 'b': setMotor(-speed, -speed); break;
    case 'L': case 'l': setMotor(-speed,  speed); break;   // pivot left
    case 'R': case 'r': setMotor( speed, -speed); break;   // pivot right
    case 'S': case 's':
    default:            setMotor(0, 0);        break;
  }
}

// signed duty: -255..255  (negative = reverse)
void setMotor(int left, int right) {
  bool lRev = left  < 0;
  bool rRev = right < 0;
  int  lPwm = constrain(abs(left),  0, 255);
  int  rPwm = constrain(abs(right), 0, 255);

  digitalWrite(PIN_IN1, lRev ? LOW  : HIGH);
  digitalWrite(PIN_IN2, lRev ? HIGH : LOW);
  digitalWrite(PIN_IN3, rRev ? LOW  : HIGH);
  digitalWrite(PIN_IN4, rRev ? HIGH : LOW);

  analogWrite(PIN_ENA, lPwm);
  analogWrite(PIN_ENB, rPwm);
}

void stopMotors() {
  analogWrite(PIN_ENA, 0);
  analogWrite(PIN_ENB, 0);
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
}

// ------------------------------- telemetry --------------------------------
// Prints the "ACK <verb> " prefix; the caller appends its own arguments, which
// keeps a char argument from being silently printed as its ASCII code.
void ack(const char *verb) {
  Serial.print(F("ACK "));
  Serial.print(verb);
  Serial.print(' ');
}
