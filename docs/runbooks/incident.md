# Incident runbook

For a security incident or a personal-data breach (GDPR Art. 33 and 34). The owner is the controller and the only operator. Keep a dated log of every step in a private note (not in this public repository).

## 1. Contain (first hour)

- Account takeover suspected (Cloudflare, GitHub, Apple, Google, RevenueCat): change the password, revoke all sessions and API tokens of that account, check its two-factor settings, read its audit log (Cloudflare: Manage Account, Audit Log; GitHub: Settings, Security log).
- Leaked secret: rotate it now (`key-rotation.md`). For `SERVER_KEY` rotate without keeping the old key as previous.
- Abuse through the service: suspend the accounts involved on the admin page (`recipe-admin.munich-tech.de`, reason code); revoke reported links by report; lower the AI Gateway rate limit in the dashboard if cost is the problem.
- Audit trail (accepted limit): the admin audit rows live in the user's object and are erased with the account. A suspension survives deletion as its reason code in the tombstone (12 months), so a suspended user who deletes and returns is still suspended; for anything longer, note the Support ID and reason in the private incident log.
- Bad deploy: `wrangler rollback --env production` to the last good version.

## 2. Assess (within 24 hours)

Write down: what happened, since when, which data (use `docs/data-inventory.md`: recipes, pictures, session hashes, subscription state, Apple refresh tokens, metrics), how many accounts, whether the data was encrypted or pseudonymous (user ids are peppered hashes; no names or emails are stored). Sources: Cloudflare audit log, Workers Logs (event names and status codes only), the admin Overview, AI Gateway analytics, RevenueCat.

## 3. Notify (within 72 hours of becoming aware)

- Supervisory authority: if the breach is likely to result in a risk to people's rights and freedoms, notify the data protection authority of the owner's German federal state within 72 hours (Art. 33 GDPR), through its online form. If not all facts are known, notify in stages. If no notification is made, record why.
- Users: if the risk is high (Art. 34 GDPR), tell the affected users without undue delay. The backend holds no email addresses: use an in-app notice (new app version or a server-side message) and the website.
- Processors: Cloudflare and RevenueCat if their systems are involved; Apple and Google if store accounts are involved.

## 4. Recover

- Restore data only as `restore.md` allows (never an erased object).
- Re-enable suspended accounts that were not involved.
- Check the alert cron, the WAF rules and `infra/provision.ts --dry-run` output against the account.

## 5. Learn

Within two weeks: what failed, what changes (code, provisioning, runbook), and a test or guard that would have caught it. Record findings in `docs/security-review.md`.
