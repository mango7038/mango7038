# PostFlow Studio — Android V3 Source Project

**Status:** Functional local Android application **source**. The responsive UI / ZIP workflow was run through browser-based smoke tests. **A new APK has not been compiled or installed in this workspace**, because Android SDK + Gradle distribution and network downloads are unavailable here. Do not confuse this repository with the earlier invalid 57 KB APK. Use Android Studio or the included GitHub Actions workflow to produce an APK, and verify it on a phone.

## What is implemented (without social APIs)

- Android app project (Java, min Android 8/API 26; compile SDK 35), responsive Dashboard, Calendar, Composer, Queue and Media Library.
- Owner-only 4–8 digit PIN first-run lock screen (PBKDF2-HMAC-SHA256 salted hash). No PIN/account credentials embedded.
- Dated ZIP import with PNG/JPEG/WebP and MP4/MOV, original bytes persisted to app-private Android storage via a streaming native bridge, not only thumbnails.
- Optional `manifest.csv` metadata for date, time, caption, category and selected destinations; folder/file-name date parsing otherwise.
- Date validation, 09:00 **Asia/Kolkata** defaults, automatic future scheduling, 15-minute same-day collision separation, past/undated ZIP items as drafts, duplicates detected by length + CRC32. **This schedules local jobs and reminders. It does not publish to social platforms yet.**
- Edit, replace, pause, mark manually shared, or delete individual posts and original media; caption suggestions in English/Hindi/Marathi.
- Per-post Instagram Feed/Story, Facebook Page Feed/Story, Google Business Profile Update and WhatsApp-share-reminder toggles, with extra account-specific placement mapping for multiple account labels. Accounts are **labels**, not logged-in or authorized until APIs are added.
- Custom festival calendar, recurring fixed-date annual festivals, private client birthdays and custom dates. Built-in **2026 Maharashtra notified festival dates** preload on first installation (plus Raksha Bandhan/Janmashtami from the CAG restricted-holiday list); a further fixed Indian special-day set can be added with one tap. Movable festivals for other years must be verified/imported. Source: https://maharashtra.gov.in/site/Upload/pdf/Public-Holiday-2026.pdf and https://cag.gov.in/uploads/media/Holiday-List-2026-069521fe6f358d0-89936988.pdf.
- Native Android alarm-based event reminders (select 7 days, 1 day, event day at 09:00 IST) and post-due manual share reminders, including when the app is closed; restored on reboot; notifications require OS permission and Android battery management can defer inexact alarms.
- **Native Android home-screen widget** with a live current-month grid, event markers and next three events. Add it through the Android launcher widget menu after installation.
- Android share sheet for original poster/video with caption, suitable for manually sharing to WhatsApp and other compatible apps. Sending still requires the user to confirm in the target app.
- Full **ZIP backup/restore including original media** using native Android share/open-document. Also optional JSON metadata-only backup in browsers.
- App data kept in Android's private local storage; no remote client details are transmitted in offline mode.

## Not implemented without official APIs / infrastructure

- Actual unattended auto-publishing to Instagram, Facebook Pages, or Google Business Profile; these require official OAuth tokens/permissions and server-side workers.
- Remote cloud sync, client SaaS signups and payments.
- WhatsApp message/Status posting without a user action (not supported by this local manual share workflow).

The application **must never silently mark planned posts as published**. With no official API connected, scheduled posts generate a due/needs-action alert.

## Generate an actual APK with Android Studio

1. Install the latest stable **Android Studio**, Android SDK Platform **35**, SDK Build Tools **35.0.0**, and JDK **17**.
2. Open this **project directory** (`settings.gradle` in project root), wait for Gradle to sync and resolve Android Gradle Plugin 8.7.3.
3. Use **Build → Build Bundle(s) / APK(s) → Build APK(s)** (debug variant), or run `gradlew.bat :app:assembleDebug` on Windows.
4. Get the APK at `app/build/outputs/apk/debug/app-debug.apk` and copy it to your Android phone to install.
5. Run on an **Android 8+ phone**. Grant notifications when prompted. On first start create your local PIN. Add `PostFlow Studio` widget from the launcher Widgets menu.
6. Import `sample/sample_dated_festivals.zip` to verify ZIP scheduling; use a future dated ZIP for actual automation after the sample dates pass.

A **GitHub Actions** build workflow is included in `.github/workflows/build-android.yml`: upload/push the project to a GitHub repository, choose **Actions → Build installable Android debug APK → Run workflow**, and download the `PostFlow-Studio-Android-Debug-APK` artifact. The debug APK is signed automatically by Android build tooling. **This workflow has not been run or verified in this environment.**

## ZIP convention

    FestivalBatch.zip
    ├── 2026-11-08_Diwali/poster.png
    ├── 2026-11-21_Promotion/offer.png
    ├── 2026-11-25_Birthday/client_wish.png
    └── manifest.csv

Optional `manifest.csv` columns:

    file,date,time,title,category,caption,destinations
    2026-11-08_Diwali/poster.png,2026-11-08,09:00,Diwali,festival,Happy Diwali!,ig_story|wa_reminder

- The ZIP's explicit date takes precedence over guessing a festival's date. Missing or past dates → drafts.
- Client birthday content stays draft by default, even when it has a future date.
- A scheduled ZIP entry's destinations/time/caption/media remain editable.
- Media import is limited to 100 MB per file, max ZIP size 200 MB / 500 MB expanded, 250 media entries. Very large files can still exceed available phone RAM during JavaScript ZIP decoding; divide large archives into smaller batches.
- Import duplicates are identified from uncompressed CRC32 and byte length. Collisions are possible; manually review important content.

## Verification performed

- Browser-based Chromium smoke test: ZIP import, stored original media via mock native bridge, two future scheduled posts, one private-birthday draft, independent Story/WhatsApp per-post selection, fixed-date festival calendar, private client birthday and Marathi caption setting all **passed**.
- JavaScript syntax passed `node --check`.
- Android Manifest, widget/layout XML files parsed successfully with XML parser.
- Java source was checked for syntax-level errors with `javac`, but full Android compilation is **not possible without Android SDK/platform JARs**.
- Android APK compilation, widget/notification device behavior and real phone installation are **NOT yet verified**.

## Project structure

- `app/src/main/assets/index.html` — responsive offline app and local planner
- `app/src/main/assets/jszip.min.js` — bundled JSZip library
- `app/src/main/java/com/postflow/studio/MainActivity.java` — Android host, file picker, owner PIN, ZIP backup restore
- `NativeBridge.java` — web ↔ native data storage, sharing and backup
- `MediaContentProvider.java` — secure read-only sharing of private media
- `EventScheduler.java`, `ReminderReceiver.java`, `BootReceiver.java` — on-device alarms/notifications
- `FestivalWidgetProvider.java`, `res/layout/widget_festival.xml` — native month widget
- `PinGate.java` — local PIN verifier
- `sample/` — test ZIP
- `.github/workflows/build-android.yml` — reproducible debug APK build CI
- `tests/` — browser workflow test script

## Notes

Be careful with client birthdays/personal details; imported private events never publish automatically. Back up regularly: uninstalling or clearing Android app data removes posts and original media (no cloud backup exists yet). PIN recovery requires clearing app data; there is no password reset server.