/// <reference types="vite/client" />
declare namespace Cloudflare {
	interface Env {
		SPIKE_DO: DurableObjectNamespace<import("./spikes/spike-do").SpikeDO>;
	}
}

declare module "*?raw" {
	const text: string;
	export default text;
}
