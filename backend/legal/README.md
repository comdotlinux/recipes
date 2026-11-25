# Legal page bodies

The Worker serves `/privacy`, `/imprint` and `/terms` (English) and `/privacy/de`, `/imprint/de`, `/terms/de` (German).
Each page body is the HTML fragment in this folder (no `<html>`, `<head>` or `<script>`; the page frame, the language
links and the security headers come from `src/legal.ts`).

| File | Source | Status |
|------|--------|--------|
| `privacy.en.html`, `privacy.de.html` | the owner's German legal-text generator, filled from `docs/data-inventory.md` | placeholder |
| `imprint.en.html`, `imprint.de.html` | the owner's German legal-text generator (name, address, contact) | placeholder |
| `terms.en.html`, `terms.de.html` | written in plain language in M6, **draft: the owner reviews it** (no lawyer is involved) | draft; the appeal contact is a placeholder |

Every placeholder contains the marker `OWNER-LEGAL-PLACEHOLDER`. Staging may show it. The production deploy must run
`bun run check:production` (in `backend/`), which fails while any file here still contains the marker.

Rules for the owner when pasting generator output: paste the HTML body only, keep it free of `<script>` and inline
event handlers (the pages' Content-Security-Policy blocks scripts anyway), and remove the marker line.
