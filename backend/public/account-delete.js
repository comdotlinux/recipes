// Web account deletion (docs/api.md, Milestone M6). Order: Turnstile, then the provider's own web sign-in with a
// server-issued nonce, then POST /v1/account/delete-web. The id token is checked on the server exactly as at app sign-in:
// Google echoes the nonce as given, Apple gets the SHA-256 (lowercase hex) of it, like the iOS app.
var turnstileToken = null;
var serverNonce = null;

function onDeleteTurnstile(token) {
	turnstileToken = token;
	var apple = document.getElementById("apple");
	if (apple) apple.disabled = false;
}

function say(text) {
	document.getElementById("status").textContent = text;
}

async function sha256Hex(text) {
	var digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
	return Array.from(new Uint8Array(digest), function (b) {
		return b.toString(16).padStart(2, "0");
	}).join("");
}

async function send(body) {
	if (!document.getElementById("confirm").checked) return say("Please tick the box first. / Bitte zuerst das Kästchen ankreuzen.");
	if (!turnstileToken) return say("Please wait for the check above. / Bitte die Prüfung oben abwarten.");
	body.nonce = serverNonce;
	body.turnstileToken = turnstileToken;
	var res = await fetch("/v1/account/delete-web", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
	if (res.status === 202) return say("Your account is being deleted. / Dein Konto wird gelöscht.");
	if (res.status === 404) return say("There is no account for this sign-in. / Zu dieser Anmeldung gibt es kein Konto.");
	say("That did not work. Reload the page and try again. / Das hat nicht geklappt. Seite neu laden und erneut versuchen.");
}

function waitFor(test, then) {
	if (test()) return then();
	setTimeout(function () {
		waitFor(test, then);
	}, 100);
}

document.addEventListener("DOMContentLoaded", async function () {
	var data = document.body.dataset;
	var res = await fetch("/v1/session/nonce", { method: "POST" });
	if (!res.ok) return say("Please try again later. / Bitte später erneut versuchen.");
	serverNonce = (await res.json()).nonce;
	if (data.googleClient) {
		waitFor(
			function () {
				return window.google && window.google.accounts && window.google.accounts.id;
			},
			function () {
				google.accounts.id.initialize({
					client_id: data.googleClient,
					nonce: serverNonce,
					callback: function (response) {
						send({ provider: "google", idToken: response.credential });
					},
				});
				google.accounts.id.renderButton(document.getElementById("google"), { type: "standard", text: "signin_with" });
			},
		);
	}
	var apple = document.getElementById("apple");
	if (apple && data.appleClient) {
		apple.addEventListener("click", async function () {
			try {
				AppleID.auth.init({ clientId: data.appleClient, scope: "", redirectURI: data.redirect, nonce: await sha256Hex(serverNonce), usePopup: true });
				var result = await AppleID.auth.signIn();
				await send({ provider: "apple", idToken: result.authorization.id_token, appleAuthCode: result.authorization.code });
			} catch (e) {
				say("Sign-in was cancelled. / Anmeldung abgebrochen.");
			}
		});
	}
});
