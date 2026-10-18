# PostFlow Studio V6 – Migration and test instructions

## Android package / old-app conflict

V6 uses `com.postflow.studio.v6` (versionCode 60), a new Android app identity. It can install alongside `com.postflow.studio` and `com.postflow.studio.new` without a package/signature conflict. **It is not an in-place update** of either old app.

Before installing V6, open the **old app → Settings → Full Backup with Media** and save the ZIP in a safe folder. In V6 select **Settings → Restore Full Backup** and verify posts, full media, and reminders. Do not delete the old app until migration is verified. Android Keystore-protected WordPress pairing cannot be transferred through a backup; pair again locally.

**Signing limitation:** GitHub's default `assembleDebug` build uses a per-run debug key. Installing **a later build over an existing V6 APK is not guaranteed** until a persistent signing keystore is set up securely in GitHub Actions. Do not publish signing keys or passwords in source control. A long-term, seamless update requires a stable signing key; changing package ID is a one-time installation workaround, not a substitute.

## Regression checklist

- App opens, PIN setup/login, calendar and widget still work.
- Settings → Appearance supports **System / Premium Dark / Minimal Light**, surviving restart.
- Create Post: Media → Caption → Schedule → Accounts → Review.
- Android multi-file chooser returns 2–10 JPEG/PNG files and displays correct slide count.
- Carousel thumbnails, left/right reorder, remove, cover indicator, save and reopen.
- WordPress pairing, Sync Accounts and Publishing Center work without displaying bearer keys.
- Scheduled timestamp and published timestamp appear separately. Missing historical timestamps are **not invented**.
- Failed jobs display server's error message and a conditional troubleshooting suggestion (may be incomplete if server returns a generic error).
- Verify local **Reminder due** status is not misrepresented as a failed remote publication.
- Publish only a safe test post and verify the server/Meta status before making a second attempt.
- Reconnect WordPress pairing after migration.

## Publishing requirements

The app cannot itself guarantee scheduled posting accuracy. WordPress server Cron, Meta permissions and publishing status must be verified separately. Re-submitting a job after a transport timeout risks duplicates.
