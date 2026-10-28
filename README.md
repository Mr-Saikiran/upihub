# UPI Hub (draft)

One place to pay with any of your UPI apps. Scan a UPI QR code (or type a UPI
ID) and UPI Hub asks which app should pay — **Kiwi** is preselected — then
hands the payment to that app, which asks for your UPI PIN as usual.

Supported apps (package IDs verified on Google Play India):

| App | Package |
|---|---|
| Kiwi (default) | `in.gokiwi.kiwitpap` |
| INDmoney | `in.indwealth` |
| Paytm | `net.one97.paytm` |
| CRED | `com.dreamplug.androidapp` |
| MobiKwik | `com.mobikwik_new` |
| Jupiter | `money.jupiter` |

## Features

- **Scan & Pay** — QR scanner (ZXing); non-UPI codes are rejected with a reason.
- **App chooser** — bottom sheet listing the six apps, default first; apps that
  are not installed are greyed out and open their Play Store page.
- **Default app** — Kiwi out of the box; change it in settings or tick
  "Make this my default" in the chooser.
- **Ask every time** switch — turn off to open the default app directly.
- **Pay to UPI ID** — manual entry (UPI ID, name, amount, note).
- **Pay from QR screenshot** — pick an image (system photo picker, no storage
  permission) or *Share* it to UPI Hub from WhatsApp, Gallery, etc.
- **Handles `upi://pay` links** from browsers/other apps and relays the paying
  app's result back to the caller.
- Optional: show other installed UPI apps (GPay, PhonePe, BHIM, ...).

## How it works

UPI Hub never touches money, PINs, or bank credentials and has no server. It
uses the standard NPCI UPI deep-link intent: the scanned `upi://pay?...` text is
forwarded **byte-for-byte** to the chosen app with `Intent.setPackage(...)`, so
merchant fields and QR signatures stay intact. The only permission is the
camera.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install: `adb install app/build/outputs/apk/debug/app-debug.apk`, or copy the
APK to the phone and allow "Install unknown apps" for your file manager.

## Known limitations

- Some UPI apps restrict or block intent-based payments to **personal** UPI IDs
  (P2P) for fraud prevention; merchant QR codes generally work. Behaviour is
  decided by each app and can change.
- A bare mobile number is not a UPI address; enter the full ID (e.g.
  `98xxxxxxxx@paytm`).
- Not all apps return a transaction status; UPI Hub then shows "status not
  reported" instead of guessing.
- NFC "UPI Tap & Pay" is not supported: it is implemented inside each PSP app
  and is not exposed to third-party apps.
- Debug-signed draft. Not tested on a physical device yet.
