declare namespace Cloudflare {
	interface Env {
		SPIKE_DO: DurableObjectNamespace<import("./spikes/spike-do").SpikeDO>;
	}
}
