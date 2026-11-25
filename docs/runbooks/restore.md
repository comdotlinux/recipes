# Restore runbook

Cloudflare keeps point-in-time recovery for SQLite-backed Durable Objects for 30 days. A restore works on one object at a time, from code running inside that object (`ctx.storage.getBookmarkForTime()` and `ctx.storage.onNextSessionRestoreBookmark()`, then the object restarts; method names from memory, UNVERIFIED: read the current Durable Objects "point-in-time recovery" page before step 3), so it needs a deployed, owner-only code path and the owner's Cloudflare account. No such path is deployed in this milestone; a restore is a deliberate, reviewed deploy.

## Rule

**Never restore an object to a point before its erasure.** An erased account (tombstone, or an empty object after `deleteAll()`) stays erased, even when the data would still be recoverable for up to 30 days. This holds also when a user asks for it: after deletion the account's data is gone by design, and restoring it would undo an erasure the user requested (GDPR Art. 17). Before any restore, read the object's `state` and `deletion_started_at` through the admin page (`adminSummary`): if the state is `deleting` or `tombstone`, or the object is empty, stop.

## When a restore is allowed

- Data loss caused by a bug or a faulty migration in an account that is `active` or `suspended`.
- The restore point is after the account's creation and before the faulty event, and after any erasure of an earlier account with the same id (a returning user's object must not get the erased account's data back).

## Steps

1. Identify the account by Support ID and the time of the faulty event (Workers Logs, admin page).
2. Check the rule above.
3. Deploy a version with a one-off, owner-only admin action that calls `getBookmarkForTime(<time>)` and `onNextSessionRestoreBookmark(<bookmark>)` in that object, then `ctx.abort()`.
4. Run it for that one object; check the user's recipes through the admin summary counts; remove the action in the next deploy.
5. Record the restore in the incident log.

## Drill record

The drill (AC40) restores a staging test object to an earlier point and checks its row counts. It needs the owner's Cloudflare account and has not been run yet; the owner records it here as a table row with date, environment, object, restore point and result.
