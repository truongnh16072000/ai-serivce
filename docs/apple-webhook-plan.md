# Apple purchase webhook implementation plan

Prepared 2026-10-14 for this Spring Boot backend. Scope selected by the owner: track purchases, renewals, cancellations, and refunds across the iOS app portfolio.

## 1. Recommended implementation

Add App Store Server Notifications V2 to the existing service, backed by its existing PostgreSQL database and `JdbcClient` repositories. One controller serves every configured app. Use Apple's official Java library for verification and server API calls; do not implement JWS cryptography ourselves. Select and pin a supported library release when implementing, checking its models against Apple's current fields. [Official library](https://github.com/apple/app-store-server-library-java).

Process verified notifications and their small database updates synchronously in one transaction. Return success only after commit. This avoids a new queue or worker for the core receiver. Apple API recovery runs separately, never inside the webhook request.

Initial deliverables: verified event history, deduplicated transactions, subscription tracking, missed-event recovery, and private SQL reports by app and environment. No iOS binary change is required to start receiving these notifications. Existing user identity is unnecessary for portfolio tracking; retain `appAccountToken` if Apple supplies it.

## 2. App configuration to fill in

Template: [`../config/apple-apps.example.json`](../config/apple-apps.example.json).

Copy it to an operator-owned `apple-apps.json`, repeat the app object for each app, and fill these fields:

| Field | Value to provide |
| --- | --- |
| `enabled` | Set to `true` after all entries and mounted key files are ready. |
| `appKey` | Stable, unique URL key, such as `money-locket`; lowercase letters, digits, and hyphens. This is a routing identifier, not a secret. |
| `displayName` | Human-readable app name for reports. |
| `bundleId` | Exact bundle identifier from App Store Connect. |
| `appAppleId` | Numeric Apple ID from App Information, not the bundle ID or team ID. Replace `null` with a positive JSON integer. |
| `serverApi.issuerId` | Issuer ID for the app's Apple developer account. |
| `serverApi.keyId` | Key ID of an In-App Purchase API key. |
| `serverApi.privateKeyPath` | Absolute path to its `.p8` key **inside the running container**. |

Apps in the same Apple account may reuse the same API credential references. Apps in different accounts use their own references. Use an In-App Purchase key from Users and Access → Integrations → In-App Purchase. Apple's library documents this key requirement for server API requests. [Key setup](https://github.com/apple/app-store-server-library-java#obtaining-an-in-app-purchase-key-from-app-store-connect).

The implementation will load this JSON at startup from `APPLE_APPS_CONFIG=/etc/ai-service/apple-apps.json`. Mount the host JSON at `/etc/ai-service/apple-apps.json` and the host key directory at `/run/secrets/apple`, both read-only through Compose. Keep `.p8` contents out of JSON, Git, logs, and API responses. Restart to apply config changes; hot reload is unnecessary.

When disabled, webhook routes return `503` and recovery does not run. When enabled, fail startup on missing files, malformed JSON, unknown fields, duplicate app keys/bundle IDs, placeholder values, invalid Apple IDs, or incomplete credentials. Limit `appKey` to `^[a-z0-9][a-z0-9-]{0,63}$`. Do not change a configured app key after its URLs are registered without updating App Store Connect.

These files are planning artifacts today. The JSON is not loaded by the current application until implementation is completed.

## 3. Endpoint and verification contract

Use one handler with these per-app URLs:

```text
POST https://<api-domain>/webhooks/apple/<appKey>/production
POST https://<api-domain>/webhooks/apple/<appKey>/sandbox
Content-Type: application/json

{"signedPayload":"<Apple JWS>"}
```

Choosing `/webhooks/**` keeps the receiver outside the existing `/api/**` app-code and per-client rate-limit interceptors. Those interceptors currently require `X-APP-CODE` and limit callers by IP. Apple authentication instead comes from cryptographic verification. Check the deployed ingress for any additional authentication that could block Apple.

Receiver sequence:

1. Accept only the configured app key, `production`/`sandbox`, and the expected JSON envelope. Bound the body to 128 KiB before deserialization, with the same limit at ingress; validate this ceiling against pilot payloads.
2. Select the verifier from the route's configured bundle ID, Apple ID, and environment. Never select an app or a trust root from unverified payload contents.
3. Verify `signedPayload` against bundled Apple root certificates, with online certificate checks enabled. Verify nested `signedTransactionInfo` and `signedRenewalInfo` when present. Confirm identifiers and environment agree across the route, notification, transaction, and renewal data; match transaction chains where identifiers are present. Use the SDK's production Apple-ID validation. [Verification example](https://github.com/apple/app-store-server-library-java#verification-usage).
4. Persist the verified event and update applicable projections atomically. Verified `TEST`, summary-only, and unfamiliar notification types remain valid even without a transaction; retain them for diagnosis rather than inventing a purchase.
5. Return `200` with an empty body after commit, including for an already committed duplicate. Return `400` for malformed data, `403` for definitive verification/identity failures, `404` for unknown routes, `413` for oversized bodies, and `503` for database or transient certificate-check failures. Do not leak verifier internals.

A valid duplicate must still pass verification. A failed transaction must leave no event marked as successfully processed. Unknown signed event types are stored and counted as unhandled, then acknowledged; missing required data for a known event is stored with a diagnostic flag without silently fabricating a projection.

Apple accepts HTTP 200–206 as success and retries unsuccessful production delivery; sandbox delivery has no automatic retry. Recovery must therefore work independently of webhook retries. [Response rules](https://developer.apple.com/documentation/appstoreservernotifications/responding-to-app-store-server-notifications).

## 4. Durable tracking model

Keep app configuration in JSON. Add four tables to the existing additive `schema.sql` initialization:

| Table | Key and data |
| --- | --- |
| `apple_notification_events` | Unique `(bundle_id, environment, notification_uuid)`; app key, raw signed payload, verified decoded data, type/subtype, signed/received timestamps, live/history origin, and handled/unhandled reason. Immutable event history. |
| `apple_transactions` | Unique `(bundle_id, environment, transaction_id)`; original transaction ID, product ID/type, purchase/expiry timestamps, transaction reason, ownership, quantity, price milliunits/currency, revocation fields, optional account token, and last transaction JWS signed date. |
| `apple_subscriptions` | Unique `(bundle_id, environment, original_transaction_id)`; subscription group, current product/transaction, next product, auto-renew status, expiry/grace/retry data, observed Apple status, source/version, last reconciliation time, and needs-reconciliation flag. |
| `apple_recovery_cursors` | Unique `(bundle_id, environment)`; last fully recovered history window and last successful recovery time. |

Use text for Apple transaction IDs and UTC instants for dates. Add indexes for app/environment/date reports and subscription chains. Keep sandbox data separate in every key and query.

Deduplicate notifications by UUID, but count sales by unique transaction ID: multiple event types may describe the same transaction. Use atomic insert/upsert semantics; concurrent duplicates must succeed without leaving a PostgreSQL transaction in an aborted state. Test the actual PostgreSQL conflict behavior.

Update each transaction only from newer signed transaction data; permit newer refund-reversal data to clear earlier revocation fields. Preserve the original events regardless of projection changes. For subscription projections, guard updates by source version, subscription chain, and the applicable current transaction. An expiry/refund for an older transaction must not overwrite a newer active renewal. Ambiguous or conflicting observations trigger reconciliation instead of guessing. Never use HTTP arrival order as subscription chronology.

## 5. Event interpretation

| Apple event | Tracking behavior |
| --- | --- |
| `SUBSCRIBED` | Record initial subscription or resubscription according to subtype; inspect ownership before counting a sale. |
| `ONE_TIME_CHARGE` | Record consumable, non-consumable, or non-renewing purchase; deduplicate by transaction ID. |
| `DID_RENEW` | Record a renewed period, including billing recovery. |
| `DID_CHANGE_RENEWAL_STATUS` | Track auto-renew enabled/disabled separately from expiry. |
| `DID_CHANGE_RENEWAL_PREF` / `OFFER_REDEEMED` | Track the signed current transaction and next renewal product; avoid a second sale count for the same transaction. |
| `DID_FAIL_TO_RENEW` / `GRACE_PERIOD_EXPIRED` / `EXPIRED` | Track billing trouble, grace completion, and expiration distinctly. |
| `REFUND` / `REFUND_REVERSED` | Update the affected transaction's refund state; preserve both events. |
| `REVOKE` | Track access revocation separately from a purchaser refund. |
| `CONSUMPTION_REQUEST` / `REFUND_DECLINED` | Record refund workflow events; neither proves a completed refund. |
| `RENEWAL_EXTENDED` / other verified events | Preserve them; update supported fields or flag for reconciliation. |

These meanings follow Apple's [notification types](https://developer.apple.com/documentation/appstoreservernotifications/notificationtype). `ONE_TIME_CHARGE` has supported production since May 27, 2025. [Apple changelog](https://developer.apple.com/documentation/appstoreservernotifications/app-store-server-notifications-changelog).

Cancellation means auto-renew was disabled; it is distinct from an expired period. Compute observed activity using expiry/grace timestamps and reconciled status, so a missed expiry notification cannot leave a subscription indefinitely active. Display unresolved or stale status explicitly. This project tracks activity and does not grant or revoke backend premium access.

## 6. Reports and monetary handling

Start with documented, read-only SQL reports accessed through an operator database session: purchases and renewals by app/day/product, auto-renew cancellations, completed/reversed refunds, and observed subscription activity. Include a coverage-start timestamp and latest successful recovery time. Use UTC dates initially; separate production and sandbox.

Count purchase/renewal transactions by their purchase date and transaction reason, rather than by number of notifications. Report cancellations/refund events on their event dates. Exclude family-shared and other non-purchased ownership from sales totals; ownership is part of Apple's signed transaction data. [Ownership](https://developer.apple.com/documentation/appstoreserverapi/inappownershiptype).

Preserve Apple's integer `price` and currency. Display price using decimal arithmetic divided by 1000; the price already includes consumable quantity, so do not multiply it again. Keep currencies separate and missing amounts unknown. Label totals as observed transaction value, not settled proceeds. Use App Store Connect reports for accounting. [Price semantics](https://developer.apple.com/documentation/appstoreserverapi/price).

Store `revocationType` and `revocationPercentage` when present. A refund may be prorated; a percentage of `100000` means 100%. Do not assume every refund reverses the entire transaction amount. If the signed fields are insufficient, report a refunded transaction with unknown amount rather than fabricate a net-revenue total. [Refund percentage](https://developer.apple.com/documentation/appstoreserverapi/revocationpercentage).

Do not expose these reports through the existing shared mobile app code. A public reporting API needs separate operator authentication and is outside this first implementation. Retain verified payloads in the private database under its backup/access controls; do not log payloads or account tokens. Plan capacity and a retention decision before production rollout; history beyond Apple's recovery window cannot be fetched again after deletion.

## 7. Recovery and existing subscribers

Use the SDK's server API client per configured app/environment. Add a sequential Spring scheduled recovery task; no Redis, Kafka, or separate service.

- Run notification-history recovery hourly and after a restart. Start from the persisted successful end time with a 15-minute overlap; for a new app start with the previous 24 hours. Fix each window's end time at job start, consume every pagination page, and advance the cursor only after the full window commits. Replays use the same verifier, deduplication, and transaction processing as live delivery.
- Retry failed API requests with bounded backoff and honor throttling. Keep the cursor unchanged on failure. Guard each app/environment recovery run against overlap with a database lock if more than one service instance runs.
- Reconcile known subscription chains daily and those flagged by ambiguous events using Get All Subscription Statuses. Use transaction history/refund history when transaction state needs repair. Verify API-returned signed transaction and renewal data before storing it, with the same concurrency/version guards as webhook updates.
- Record recovery lag, last success, failures, unhandled events, verification failures, and duplicate deliveries through the existing Actuator/logging facilities. Warn on growing recovery lag or stale subscription snapshots; do not equate a quiet app with a broken webhook.

Notification history covers attempted delivery for 180 days in production and 30 days in sandbox. It is historical event data, not a current subscription snapshot. [History API](https://developer.apple.com/documentation/appstoreserverapi/get-notification-history), [history versus current state](https://developer.apple.com/documentation/appstoreserverapi/notificationhistoryresponse).

For onboarding, provide an operator replay procedure for the available history window. Old subscribers with no available notification history require known transaction IDs from existing app/server data to seed status lookup. This API does not enumerate every subscriber in an app. Label tracking coverage accordingly; do not promise a complete historical customer list from webhooks alone.

## 8. Implementation scope and order

1. **Configuration and trust:** Add JSON loading/validation, pinned official SDK, bundled Apple PKI roots, app/environment verifier construction, and focused config/verifier tests.
2. **Receiver and ledger:** Add webhook controller/request validation, JDBC event storage, additive schema, error handling, and committed-duplicate behavior.
3. **Tracking and reports:** Add transaction/subscription projections, ordering safeguards, event interpretation, and private report queries.
4. **Recovery:** Add scheduled history/status reconciliation, persisted cursors, replay runbook, and recovery metrics.
5. **Deployment and app onboarding:** Read back each app's Apple ID/bundle ID, confirm API access, and inspect existing notification URLs before changing them. Preserve any existing subscription-provider delivery requirements. Add read-only config/key mounts, ingress route/body-limit checks, sandbox pilot, and per-app production registration.

Expected touched locations:

| Location | Planned change |
| --- | --- |
| `build.gradle` | Official Apple server library dependency only. |
| `src/main/java/com/ai/service/apple/` | Configuration, controller, verification/processing service, JDBC repository, and recovery service. Follow existing package conventions; no general webhook framework. |
| `src/main/resources/schema.sql` | Four additive tables and required indexes. |
| `src/main/resources/application.properties` | Path to the external JSON config; default disabled when absent. |
| `src/main/resources/apple/` | Public Apple root certificates from Apple PKI. |
| `src/main/java/com/ai/service/error/ApiExceptionHandler.java` | Safe webhook error mapping if shared advice is used. |
| `src/test/java/com/ai/service/apple/` and test resources | Config, verification, webhook, persistence/projection, and recovery fixtures/tests. |
| `compose.yaml`, deployment documentation | Read-only config/key mounts and operator runbook; inspect actual ingress before registration. |
| `README.md` | Endpoint/configuration/report/replay documentation when implementation ships. |

Existing app-code and rate-limit configuration needs no modification with the proposed route. Keep chat, image, conversation, and Telegram behavior intact. The checkout already contains unrelated changes; preserve them.

## 9. Required verification and rollout

- Reject missing/invalid signatures, untrusted certificates, wrong bundle/Apple ID/environment, invalid nested signatures, malformed/oversized bodies, and unconfigured app keys. Exercise real SDK verification with appropriate fixtures; controller mocks alone are insufficient.
- Verify concurrent duplicates, transaction rollback on database failure, same transaction in different events, older expiry after newer renewal, refund then reversal, cancellation before expiry, grace-period boundaries, partial refunds, absent monetary fields, and family sharing.
- Confirm `TEST`, summary-only, and future event types are stored safely. Confirm config startup validation and disabled behavior.
- Test paginated recovery, restart from cursor, repeated history, API throttling/failure, and reconciliation racing live events. Run repository tests on H2 and relevant conflict/locking checks on PostgreSQL.
- Run `./gradlew clean build` during implementation. Exercise real sandbox notifications for one app, inspect stored data, replay duplicates, and verify API test-notification status. Local StoreKit fixtures do not prove external webhook delivery.
- Deploy receiver before registering URLs. In each app's App Information → App Store Server Notifications, configure explicit production and sandbox URLs using Version 2. [App Store Connect setup](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/enter-server-urls-for-app-store-server-notifications).
- Pilot one app, verify production `TEST` delivery and the next genuine signed transaction, then enroll the remaining JSON entries. Keep previous URL settings for rollback, preserve database records, and recover the interruption window after any rollback.

Done means every configured app passes delivery testing in both environments, invalid payloads cannot alter tracking data, duplicates do not inflate sales, missed events can be recovered, and private reports show coverage and freshness.

Deferred: Telegram delivery, dashboards, public analytics endpoints, user-level entitlement enforcement, automatic consumption-data submission, and financial-report import. No App Store Connect settings or production services are changed by this planning task.
