# PostFlow Studio — Pending Integration / Regression Checklist
Updated: 2026-10-19

This repository contains the **Android V3 offline-first source snapshot**, not a verified fully functioning social auto-publisher or all later #35–#45 features. Android build and installation still require validation. Do not publish test-only UI states as proof of publishing.

## Release blockers
- Instagram Feed scheduled posts must publish reliably through official authenticated APIs.
- Story publish outcome, reminder dismissal, and accurate status reconciliation.
- Carousel multi-image selection, crop/aspect-ratio validation, feed/story upload.
- Scheduling must not publish immediately; schedule time must match WordPress/backend time in Asia/Kolkata.
- Eliminate multi-tap Schedule button; ensure idempotency/deduplication.
- Show WP connection dialog when disconnected; correct verified/connected state.
- Multiple Instagram/Facebook handles and account switching.
- Reschedule posts and change Story vs Feed after creation.
- Captions: AI suggestions, plus TXT filename-to-caption mapping.
- Import ZIP filename dates/times with pre-scheduling review, duplicates check.
- Android home-screen widget, notifications and reminder testing on real device.
- Stable APK package/signing identity, updates and backup/restore.

## Planned feature groups (not verified as implemented)
- #35 Smart Bulk Scheduler
- #36 Brand Content Library
- #37 Publishing Health Monitor
- #39 Caption Templates
- #40 Festival Auto-Preparation
- #42 Media Quality Checker
- #45 Complete Backup & Restore

## Constraints
- GraphicaNest only; no IntrendZ.
- Initial use private/internal.
- Instagram, Facebook Pages, Google Business Profile intended; integration must use official APIs and valid permissions.
- WhatsApp share reminder remains user-initiated manual sharing.
- Per-post independently selected destinations and account/placement mapping.
- UI should support light/dark mode, smooth responsive design, editable calendar and creative editor.
- Preserve data and signing continuity across APK updates.

## Build
Run the root workflow `.github/workflows/postflow-build-android.yml`. The project lives in `postflow-studio/`. Icon assets are stored as base64 for reliable text-based source transfer; run `bash prepare-assets.sh` inside `postflow-studio` before local Android Studio build. The GitHub workflow runs it automatically.

**Neither an Actions build result nor APK installation nor production social posting is verified by this document.**
