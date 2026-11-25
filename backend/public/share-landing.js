// Share link landing page (docs/api.md, Milestone M5). On Android, "Open in the Recipes app" becomes an intent link for
// the same https URL, which opens the app if it is installed and falls back to Google Play otherwise. Elsewhere the link
// stays as it is. The page never shows anything of the recipe.
document.addEventListener("DOMContentLoaded", function () {
	var link = document.getElementById("open-app");
	if (!link || !/Android/i.test(navigator.userAgent)) return;
	var fallback = encodeURIComponent(link.href);
	link.href = "intent://" + location.host + location.pathname + "#Intent;scheme=https;package=com.linux.recipes;S.browser_fallback_url=" + fallback + ";end";
});
