# Release checklist (manual)

Checks that need a person and real devices, because automation cannot do them: real provider sign-in, the
live Turnstile challenge, the camera, and the device's own storage. The automated flows in `e2e/` use the
test identity provider of the local Worker entry and do not replace any item here.

Run every item on one physical Android phone and one physical iPhone against **staging**, with a release
build signed as it will ship (Android: the release build from Play internal testing; iOS: the TestFlight
build). Record the date, build number, device model and OS version next to each tick. Later milestones add
their sections below.

## M1: sign-in, recipes, offline, sign-out

### Before you start

- Delete the app if it is installed, so that you start without cached data.
- Have a Google account (Android) and an Apple ID (iOS) that you can use for testing, with two-factor
  authentication on, as on a normal user's phone.

### Items

- [ ] **Google sign-in on Android.**
  Do: open the app, tick the 16-or-older confirmation, tap "Sign in with Google" and choose the test account in
  the system account picker.
  Expect: the system Google account sheet appears (not a browser page), the recipe list opens empty, and
  Settings shows a Support ID. Signing in without the confirmation ticked is not possible.

- [ ] **Apple sign-in on iOS.**
  Do: open the app, tick the 16-or-older confirmation, tap "Sign in with Apple" and confirm with Face ID or the
  passcode. Run it once with "Hide My Email" and once with "Share My Email" (between the two, remove the app
  under Settings > Apple ID > Sign in with Apple).
  Expect: the system Apple sheet appears, the recipe list opens, and both runs end signed in.

- [ ] **Turnstile step.**
  Do: during each sign-in above, watch the step between the provider sheet and the recipe list.
  Expect: the Turnstile check finishes on its own or shows a single challenge that can be completed; no
  Cloudflare error page and no blank screen. With airplane mode on before tapping sign-in, the app shows a
  network error and stays on the sign-in screen.

- [ ] **Same account on the second platform.**
  Do: sign in with the same provider account on a second device of the same platform, if one is available.
  Expect: the same Support ID on both devices.

- [ ] **Create a recipe with a camera photo.**
  Do: create a recipe with a title, two ingredients, two steps, and one picture taken with the camera from
  inside the app (grant the camera permission when asked). Save.
  Expect: the recipe appears at the top of the list with the picture; the detail screen shows the picture
  upright (not rotated) and sharp enough to read text on a packet; the camera permission prompt names the
  reason for the access.

- [ ] **Read it in airplane mode.**
  Do: after the recipe has synced, force-quit the app, switch on airplane mode (and turn Wi-Fi off), open the
  app.
  Expect: the list and the detail screen show the recipe and its picture; no endless spinner; an offline note
  instead of an error dialog. Switch airplane mode off: the app syncs without a restart.

- [ ] **Sign-out leaves no data.**
  Do: Settings > Sign out. Then, with the app still installed:
  - Android: Settings > Apps > Recipes > Storage shows user data close to the size of a fresh install;
    `adb shell run-as` is not available on the release build, so compare with a fresh install's figure.
  - iOS: Settings > General > iPhone Storage > Recipes shows "Documents & Data" close to a fresh install's figure.
  Then open the app in airplane mode.
  Expect: the sign-in screen, no recipe titles or pictures anywhere (also not in the app switcher snapshot),
  and signing in again pulls the recipe back from the server.

- [ ] **Session after reinstall.**
  Do: delete the app and install it again.
  Expect: the app starts signed out (the token is not restored from a device backup or the keychain of the old
  install).

## Backend production deploy (M6)

- [ ] **Legal texts filled.**
  Do: paste the privacy policy and imprint from the legal-text generator into `backend/legal/` (README there), review the draft terms and fill the appeal contact.
  Expect: `cd backend && bun run check:production` passes (it fails while any `OWNER-LEGAL-PLACEHOLDER` is left).
- [ ] **Web deletion.**
  Do: open `https://recipe.munich-tech.de/account/delete`, sign in with Apple, then with Google, on test accounts.
  Expect: 202 and the account answers 401 in the app; the Apple token is revoked (Apple ID settings no longer list the app).
