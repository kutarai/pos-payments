# Payments

Taking money, on any terminal.

Card (EMV contact, contactless and magstripe), mobile money, QR and cash — the flow, the switch
conversation, the screens and the slip. Nothing in here knows what make of terminal it is running
on, and nothing in here knows what is being sold.

Used by two applications today: SynergyPOS, at the point of sale, and ProCity, which takes the
same payments on the same counter. It is a library rather than part of either because two
copies of a payment flow drift apart in exactly the places that cost money.

## What is in it

| Package | What it holds |
|---|---|
| `model` | `Money`, the `Payment` kinds, transaction type and status |
| `terminal` | The ports a terminal implements — identity, PIN pad, scanner, onboard printer — and the `Terminals` registry an application's start-up fills; also `HardwareSerial` (choosing a trustworthy serial from what a unit reports), `PinEncryptionScheme` and the terminal's identity snapshot |
| `card` | The EMV port, the `CardPaymentDriver` a screen drives and its registry, the drivers that guard or refuse it, TLV parsing, the flow rules every make shares, and `EmvConfiguration`: the one source of the AID table and CA public keys that each device library adapts for its own kernel |
| `switching` | gRPC to the payment switch, and the protos both sides share |
| `qr` | EMVCo QR payloads and the bitmap a customer scans |
| `cash` | `CashTender` — what is owed, what was handed over, what goes back |
| `ui` | The card screen and the payment dialogs (mobile money, QR, cash, crypto, banking) |

Printing — the Bluetooth and Wi-Fi Direct transports, printer settings, ESC/POS and the setup
screen — lives in the sibling `Printers` library (`../Printers`), which both hosts use directly.

## Supporting a new terminal

A terminal is its own library, beside [CS20](../CS20) (Ciontek) and [Z150](../Z150) (ZCS SmartPos).
It implements `card.CardPaymentDriver`, and as much of `terminal` as the hardware has, and reads the
EMV tables from `EmvConfiguration` rather than keeping its own. It exposes
`zw.co.unipay.terminal.TerminalSupport`, under that same fully-qualified name in every device
library, and declares its vendor's manifest components in its own `AndroidManifest.xml`. The
application then registers it, once, at start-up, naming no vendor:

```kotlin
TerminalSupport.install(applicationContext, switchIntegration) { merchantConfigNow() }
```

`install` fills the two registries, `CardPaymentDrivers` and `Terminals`; every payment screen asks
them, so nothing above the driver changes. An application build depends on exactly one device
library. See either library's README for what `install` registers and why the two differ.

## Using it

Either include the build, or point a project directory at it:

```kotlin
// settings.gradle.kts
include(":payments")
project(":payments").projectDir = file("/path/to/shared/Payments/payments")
```

Applications that run on a terminal depend on that terminal's device library (`CS20` or `Z150`),
which exposes this as `api`.

## Building on its own

```
./gradlew :payments:assembleDebug :payments:testDebugUnitTest
```

The tests are the arithmetic, the wire formats and the rules that must not differ between makes of
terminal: cash tender, EMV QR payloads, switch requests and reversals, the EMV tables and TLV data,
the guarding and refusing card drivers, the `Terminals` registry, serial selection, the PIN
encryption scheme and the identity snapshot. They need no device, and they are the ones worth
running before anything reaches a counter.
