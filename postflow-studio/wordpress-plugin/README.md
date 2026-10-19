# PostFlow Studio WordPress Connector (foundation)

**Status:** Backend queue scaffold, **not** an Instagram/Facebook/Google Business Profile auto-publisher. Use only in staging until authenticated endpoints, cron and device integration are tested.

## Install
Zip the `postflow-studio-connector/` folder (the folder containing the PHP file), upload it in WordPress → Plugins → Add New → Upload Plugin, then activate.

## Authentication
REST calls require an authenticated administrator with `manage_options`. Use WordPress **Application Passwords over HTTPS** for a trusted service client. Never embed a WordPress Application Password in a downloadable Android APK or publicly served browser JavaScript. For the Android app, implement a secured backend token exchange or authenticated proxy before connecting directly.

## Routes (WP site prefix /wp-json/postflow/v1)
- `GET /health` — version, next WP-Cron, and publishing_enabled=false
- `GET /jobs` — latest 100 jobs
- `GET /jobs/{id}` — one job
- `POST /jobs` — create/upsert by unique client_key (reusing existing jobs, not publishing)

Example JSON body:

```json
{
  "client_key": "local-post-123",
  "scheduled_at_utc": "2026-11-02T03:15:00Z",
  "destinations": ["ig_feed", "fb_page"],
  "caption": "Festival greeting"
}
```

**Schedule times are UTC**. Convert from Asia/Kolkata before POSTing. WordPress cron is traffic-driven; low-traffic sites need an external scheduler calling the standard `wp-cron.php` endpoint or an actual server cron. Five-minute recurrence is best-effort and may be delayed.

At due time, the scaffold marks a job `needs_action`. It does **not** upload any poster/reel, call social APIs, or mark a post `published`. Media processing, rate limits, valid Meta/GBP OAuth approvals, idempotent provider publishing, post status reconciliation, retries, account switching and security hardening remain pending.

The previously created `postflow-connect-v2.2.0.zip` is a separate legacy plugin build. This scaffold does **not** overwrite or claim to upgrade its installation. Inspect/merge that plugin source and create a migration plan before production deployment.
