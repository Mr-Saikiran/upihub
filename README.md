# UPI Hub

One scanner for all your UPI apps. Open the app and it is already scanning;
point it at any UPI QR code and pick which app pays — your default app is
preselected. On first launch UPI Hub lists every installed UPI app so you can
pick that default.
The chosen app asks for your UPI PIN and completes the payment.

Application ID: `techswitch.upihub` · min Android 7.0 (API 24) · target API 36

Known apps (any other installed UPI app is detected too):

| App | Package (verified on Google Play India) |
|---|---|
| Kiwi | `in.gokiwi.kiwitpap` |
| INDmoney | `in.indwealth` |
| Paytm | `net.one97.paytm` |
| CRED | `com.dreamplug.androidapp` |
| MobiKwik | `com.mobikwik_new` |
| Jupiter | `money.jupiter` |
| Google Pay | `com.google.android.apps.nbu.paisa.user` |
| PhonePe | `com.phonepe.app` |
| super.money | `money.super.payments` |
| BHIM | `in.org.npci.upiapp` |

## Features

- **Opens into a live QR scanner** (asks for the camera on first launch), with
  flashlight and a scan-window overlay. The same code does not reopen the
  chooser right after you cancel or return from a payment.
- **App chooser** — your installed payment apps in your order, default
  preselected. The list scrolls so the pay button always stays
  on screen.
- **Amount entry for codes without one** — personal QR codes usually carry no
  amount, and some apps (e.g. Kiwi) lock the amount field for links opened
  from another app. UPI Hub asks for the amount and adds it to the link; codes
  that already carry an amount are forwarded untouched.
- **Payment apps** (Settings) — drag to reorder, switch apps on or off, sort
  A–Z or reset. Every installed app that takes UPI links is listed and shown
  by default, including apps whose link handler omits the default category
  (they are opened explicitly). A known app that is installed but takes no
  UPI links is listed with that explanation. The default app is always shown.
- **Home-screen widget** — a one-tap "Scan & Pay" icon (Settings → Add scan
  widget, or long-press the home screen → Widgets → UPI Hub).
- **Default app** — chosen on first launch from all installed UPI apps;
  change it from the home card, settings, or "Make this my default" in the
  chooser.
- **Ask every time** — turn off to open the default app directly.
- **Pay from screenshot** — system photo picker (no storage permission) or
  *Share* an image to UPI Hub.
- **Pay to UPI ID** — manual entry with amount and note.
- **Handles `upi://pay` links** from browsers/other apps and relays the paying
  app's result back to the caller.
- Light and dark themes; adaptive launcher icon.

## How it works

UPI Hub never touches money, PINs or bank credentials and has no server. It
uses the standard NPCI UPI deep-link intent: the scanned `upi://pay?...` text is
forwarded **unchanged** (apart from an amount the payer enters for codes
without one) to the chosen app with `Intent.setPackage(...)`, so
merchant fields and QR signatures stay valid. The only permission is the camera.

Source layout (`app/src/main/java/dev/upihub/`): `UpiPayment` parses and builds
payment links, `UpiResponse` reads the app's result, `UpiApps` discovers
installed apps, `PaymentFlow` is the chooser, `MainActivity` the scanner home,
`RouterActivity` the `upi://` link handler.

## Build and test

Requires JDK 17+ and the Android SDK (platform 36).

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Unit tests include Robolectric workflow tests that drive the real activities
(chooser, default app, intent sent to the paying app, `upi://` relay) and write
screenshots to `app/build/screenshots/`. If Maven Central rate-limits
Robolectric's runtime download, add
`-ProbolectricRepo=https://maven-central.storage-download.googleapis.com/maven2`.

## Release signing (Google Play)

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/path/to/upihub-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Then `./gradlew bundleRelease` produces the signed Play bundle at
`app/build/outputs/bundle/release/app-release.aab` (`assembleRelease` gives a
signed APK). Enrol in **Play App Signing** when creating the app: this key is
then only the *upload* key and can be reset through Play support if lost.

CI (`.github/workflows/android.yml`) tests and builds a debug APK on every
push. **Actions → Android → Run workflow** on `master` (or pushing a `v*` tag
that matches `versionName`) builds the signed APK and AAB and publishes a
**GitHub Release** `v<versionName>` with the APK attached, using
`docs/release-notes/<tag>.md` as its text. It needs these repository
secrets: `UPIHUB_KEYSTORE_BASE64` (`base64 -w0 upihub-upload.jks`),
`UPIHUB_KEYSTORE_PASSWORD`, `UPIHUB_KEY_ALIAS`, `UPIHUB_KEY_PASSWORD`.

## Publishing

See [docs/PUBLISHING.md](docs/PUBLISHING.md) for the Google Play walkthrough,
[docs/PRIVACY.md](docs/PRIVACY.md) for the privacy policy draft, and
`docs/store/` for the icon and feature graphic.

## Known limitations

- Some UPI apps restrict or block intent-based payments to **personal** UPI IDs
  (P2P) for fraud prevention; merchant QR codes generally work. Each app decides.
- A bare mobile number is not a UPI address; enter the full ID.
- Not all apps return a transaction status; UPI Hub then says so rather than guessing.
- NFC "UPI Tap & Pay" is implemented inside each payment app and is not exposed
  to other apps.
- Verified with unit and Robolectric tests only; not yet run on a physical device.
