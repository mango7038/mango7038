# PostFlow Studio 8.3.0 — Instagram comment automation

This is an **update to the existing Android app** (package `com.postflow.studio.permanent`, versionCode 20). Do not create a new Android app.

## Included
- Phase 1: Android rule editor, account/media scope, keyword matching, public reply and private reply templates, local persistence.
- Phase 2: Node.js webhook receiver, Meta HMAC SHA-256 verification, webhook subscription verification, account routing.
- Phase 3: queue processing, duplicate-comment protection, private reply then public reply, limited retry and failure logging.
- Phase 4: Android analytics dashboard, server delivery logs, release workflow syntax validation.

## Setup required before live sending
1. Deploy `automation-server/` to an HTTPS Node.js host with durable storage (for production, replace the JSON file with a transactional database and a durable job queue).
2. Set server environment variables:
   - `POSTFLOW_AUTOMATION_API_KEY`: strong random owner-only key
   - `META_APP_SECRET`: Meta app secret
   - `META_WEBHOOK_VERIFY_TOKEN`: independent random verification token
   - `META_GRAPH_VERSION`: supported Instagram Graph API version, defaults to v23.0
   - `META_INSTAGRAM_ACCOUNTS_JSON`: JSON map, e.g. `{"instagram:1784...":{"igUserId":"1784...","accessToken":"SERVER_SIDE_ACCESS_TOKEN"}}`
   - `POSTFLOW_AUTOMATION_DATA_FILE`: persistent writable JSON data file location
3. Configure Meta app Instagram comment webhook callback at `https://YOUR_HOST/webhook` with the verification token. Subscribe the relevant Instagram account to the comments webhook and obtain approved permissions for comment moderation and messaging.
4. Open PostFlow > More > Auto Reply, create rules, enter HTTPS server URL and owner-only API key, tap **Sync Rules**. Use **Refresh Analytics** for delivery logs.
5. Verify with a permitted test Instagram account before enabling production rules.

## Limitations and safety
- This repository does **not** deploy the server, configure Meta credentials, or grant App Review approval.
- No Instagram access token or Meta app secret should be entered into the Android app.
- The Android app currently stores the backend owner API key in local app state. This is suitable only for a trusted single-owner prototype; **do not distribute** this build to multiple untrusted client users with the owner key. Before a multi-tenant launch, implement per-user OAuth/session auth and server-side account ownership checks.
- A private reply is limited by Meta eligibility and time windows; only one private reply per comment. A public reply claiming a DM was sent should only be used after confirming private reply success.
- The JSON persistence and in-process worker are a prototype, not a horizontally scalable production job system. Deploy only one server instance and use a durable volume.
- Ensure the Meta API endpoint version and payloads are validated against your app's approved Instagram Login/Facebook Login integration before production.
