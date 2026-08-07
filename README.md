# Payments

Taking money, on any terminal.

Card (EMV contact, contactless and magstripe), mobile money, QR and cash — the flow, the switch
conversation, the screens and the slip. Nothing in here knows what make of terminal it is running
on, and nothing in here knows what is being sold.

Used by SynergyPOS today. It is a library rather than part of that application because an EFT
application on the same counter takes the same payments in the same way, and two copies of a
payment flow drift apart in exactly the places that cost money.

## What is in it

| Package | What it holds |
|---|---|
| `model` | `Money`, the `Payment` kinds, transaction type and status |
| `terminal` | The ports a terminal implements — identity, PIN pad, scanner, onboard printer |
| `card` | The EMV port, the `CardPaymentDriver` a screen drives, TLV parsing |
| `switching` | gRPC to SynergySwitch, and the protos both sides share |
| `qr` | EMVCo QR payloads and the bitmap a customer scans |
| `cash` | `CashTender` — what is owed, what was handed over, what goes back |
| `printing` | ESC/POS bytes, receipt layout, the Bluetooth transport |
| `ui` | The card screen and the mobile-money, QR and cash dialogs |

## Supporting a new terminal

Implement `card.CardPaymentDriver`, and as much of `terminal` as the hardware has. Then, once, at
application start-up:

```kotlin
CardPaymentDrivers.register { context -> AcmeCardPaymentDriver(context) }
```

Every payment screen asks the registry, so nothing above the driver changes. `CS20` is the
worked example.

## Using it

Either include the build, or point a project directory at it:

```kotlin
// settings.gradle.kts
include(":payments")
project(":payments").projectDir = file("/path/to/shared/Payments/payments")
```

Applications that also want the Ciontek hardware should depend on `CS20` instead, which exposes
this as `api`.

## Building on its own

```
./gradlew :payments:assembleDebug :payments:testDebugUnitTest
```

The tests are the arithmetic and the wire formats — change, receipt widths, ESC/POS sequences.
They need no device, and they are the ones worth running before anything reaches a counter.
