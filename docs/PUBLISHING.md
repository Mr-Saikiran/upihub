# Publishing UPI Hub on Google Play

A step-by-step guide for `techswitch.upihub`. Play Console screens and rules
change; where this guide and the Console disagree, follow the Console and
[Play Console Help](https://support.google.com/googleplay/android-developer).

## 0. What you already have

| Item | Where |
|---|---|
| Signed app bundle (`.aab`) | `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`, or the CI release on a `v*` tag |
| Upload key (`upihub-upload.jks` + `keystore.properties`) | Your password manager. **Never commit or share it.** |
| App icon 512×512 | `docs/store/icon-512.png` |
| Feature graphic 1024×500 | `docs/store/feature-graphic-1024x500.png` |
| Privacy policy text | `docs/PRIVACY.md` (needs a public URL, see step 4) |
| Target API | 36 (meets Play's current requirement for new apps) |

Still needed: **phone screenshots** (2–8, from a real phone; see step 6) and
a **support email**.

## 1. Create a developer account

1. Go to <https://play.google.com/console/signup> with the Google account
   that will own the app.
2. Choose the account type:
   - **Personal** — quickest. New personal accounts must run a **closed test
     with at least 12 testers opted in for 14 days in a row** before they can
     publish to production (step 8).
   - **Organization** — needs a **D-U-N-S number** for your company (free, can
     take up to ~30 days) and is the better fit for a finance app published
     under a company name.
3. Pay the one-time **US$25** registration fee and complete **identity
   verification** (government ID; organizations also verify the business).
   Verification can take a few days.

## 2. Create the app

Play Console → **Create app**:

- App name: `UPI Hub` (see the naming note in step 10)
- Default language: English (India) – en-IN
- App or game: **App** · Free or paid: **Free**
- Accept the declarations → **Create app**.

## 3. Upload the first build (internal testing)

1. **Test and release → Testing → Internal testing → Create new release.**
2. When asked about app signing, keep **Google Play App Signing** (default).
   Google then holds the app signing key; your `upihub-upload.jks` is only
   the *upload key*, and it can be reset through Play support if lost.
3. Upload `app-release.aab`. Release name `0.4.0`; release notes e.g.
   "First test release."
4. Add yourself (and a few people) as internal testers by email, save,
   **Review release → Start rollout**. Testers install it from the opt-in
   link. This is the fastest way to check the Play-signed build on real phones.

## 4. App content (Policy → App content)

Fill in every declaration; the dashboard shows what is missing.

| Section | Answer for UPI Hub |
|---|---|
| **Privacy policy** | A public URL to `docs/PRIVACY.md` (fill in the date and support email first). Options: a public GitHub repo/Gist, GitHub Pages, Google Sites. The repo is private, so a raw link to it will not work. |
| **App access** | All functionality is available without special access (no login). |
| **Ads** | No, the app does not contain ads. |
| **Content rating** | Complete the IARC questionnaire: category *Utility/Productivity/Other*; no violence, sexual content, gambling, user interaction or sharing of location. Expected rating: Everyone/3+. |
| **Target audience** | 18 and over (payments). Not designed for children. |
| **News app** | No. |
| **Data safety** | See below. |
| **Government app** | No. |
| **Financial features** | Declare the payment-related feature that matches the app (it launches UPI payment apps; it does not move money, hold funds, lend or connect to NPCI). Be ready to explain this in plain words if Google asks. |
| **Health** | No health features. |

### Data safety answers

UPI Hub has **no internet permission**, so nothing it handles can leave the
device except when the user taps pay and the payment request goes to the UPI
app they picked (a user-initiated transfer).

- Does your app collect or share any of the required user data types? **No.**
- (If asked) Is all user data encrypted in transit? Not applicable, no data is transmitted.
- Can users request deletion? Not applicable; all data stays on the device and is removed by clearing storage or uninstalling.
- Third-party SDKs: only AndroidX, Material Components and ZXing, none of which collect data.

If you later add analytics, crash reporting, ads or a backend, these answers
and the privacy policy must change before that release.

## 5. Store settings

**Grow → Store presence → Store settings**: category **Finance**, contact
email (required, shown publicly), optional website.

## 6. Main store listing

**Grow → Store presence → Main store listing**:

- **App name** (≤30 chars): `UPI Hub`
- **Short description** (≤80 chars):
  `Scan any UPI QR and pay with the UPI app you choose: one scanner for all.`
- **Full description** (≤4000 chars), starting point:

  > UPI Hub opens straight into a QR scanner. Point it at any UPI QR code and
  > choose which of your UPI apps pays, with your favourite preselected.
  >
  > • One scanner for every UPI app on your phone
  > • Pick a default app, reorder the list, hide apps you don't use
  > • Pay from a QR screenshot or by typing a UPI ID
  > • Enter the amount when a QR code has none
  > • Home-screen Scan & Pay widget
  > • Light and dark themes
  >
  > Private by design: UPI Hub has no internet access, no account, no ads and
  > no tracking. It never sees your UPI PIN or bank details; the UPI app you
  > choose completes the payment.
  >
  > UPI Hub is an independent app and is not affiliated with NPCI or with the
  > payment apps it can open.

- **App icon**: `docs/store/icon-512.png`
- **Feature graphic**: `docs/store/feature-graphic-1024x500.png`
- **Phone screenshots**: 2–8 images, 16:9 or 9:16, each side 320–3840 px.
  Take them on your phone (scanner, chooser, Payment apps screen, widget).
  Avoid showing real UPI IDs, names or amounts, and prefer views where
  third-party app logos are not the focus.

## 7. Countries and pricing

**Monetize/Release → Production → Countries/regions**: add **India** (and
any others). Price: Free.

## 8. Closed testing (personal accounts) and production access

1. **Testing → Closed testing → Create track**, upload the same or a newer
   `.aab`, add at least **12 testers** (email list or Google Group).
2. Testers must opt in and stay opted in for **14 consecutive days**; ask
   them to install and actually use the app.
3. After 14 days, the Dashboard shows **Apply for production**. Answer the
   questions about your test (what you learned, what changed).
4. Google reviews the application, typically within about 7 days.

Organization accounts can usually go straight to production.

## 9. Production release

1. **Release → Production → Create new release**, upload the `.aab` (or
   promote the tested release), add release notes.
2. Use a **staged rollout** (e.g. 20% → 50% → 100%) for safety.
3. **Review release → Start rollout to production.** First reviews can take
   several days; you'll get an email when it's live.

### Later updates

1. Bump `versionCode` (must always increase) and `versionName` in
   `app/build.gradle.kts`.
2. Build with `./gradlew bundleRelease`, or push a tag (`git tag v0.5.0 &&
   git push origin v0.5.0`) once the four `UPIHUB_*` secrets are set, then
   download the bundle from the workflow run.
3. Upload to a testing track first, then promote to production.

## 10. Policy notes worth reading before submitting

- **Name and trademarks.** "UPI" is a trademark of NPCI, and Paytm, PhonePe,
  Google Pay, Kiwi, CRED and others are their owners' brands. Keep the
  independence disclaimer in the description, don't use other apps' logos in
  the icon, feature graphic or screenshots' focus, and consider a name such
  as "TechSwitch Pay Scanner" if Google or a brand owner objects to "UPI Hub".
- **Financial apps.** Google may ask finance apps for extra information.
  UPI Hub does not hold money, lend, or connect to banks/NPCI; it only opens
  other apps with standard UPI links. Say so clearly.
- **Permissions.** Only the camera is requested, and it is explained in the
  app and privacy policy. Don't add `QUERY_ALL_PACKAGES`; the app uses
  targeted `<queries>` instead, which Play prefers.
- **Real-device testing.** The app has only been verified with automated
  tests and Robolectric so far. Test the Play-signed internal build on real
  phones, with each UPI app, before production.
