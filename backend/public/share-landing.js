// Share link landing page (docs/api.md, Milestone M5). The link is https://<host>/s#<token>: the token is in the fragment,
// which the browser never sends to a server. On Android, "Open in the Recipes app" becomes an intent link that opens the
// app with the token as the string extra "shareToken" (an intent URL has no room for a second fragment) and falls back to
// Google Play. Elsewhere the link stays as it is. The page never shows anything of the recipe.
document.addEventListener("DOMContentLoaded", function () {
	var link = document.getElementById("open-app");
	var token = location.hash.slice(1);
	if (!link || !/^[A-Za-z0-9_-]{1,200}$/.test(token) || !/Android/i.test(navigator.userAgent)) return;
	var fallback = encodeURIComponent(link.href);
	link.href = "intent://" + location.host + "/s#Intent;scheme=https;package=com.linux.recipes;S.shareToken=" + token + ";S.browser_fallback_url=" + fallback + ";end";
});
