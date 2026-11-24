// Hands the Turnstile token to the app exactly once (docs/api.md, "Turnstile page").
var tokenSent = false;
function onTurnstileToken(token) {
	if (tokenSent) return;
	tokenSent = true;
	if (window.RecipesBridge) window.RecipesBridge.onToken(token);
	else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.turnstile) window.webkit.messageHandlers.turnstile.postMessage(token);
}
