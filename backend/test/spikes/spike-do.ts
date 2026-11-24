import { DurableObject } from "cloudflare:workers";

/** Test-only Durable Object for spike S11. Records every alarm and request step in its own SQLite table. */
export class SpikeDO extends DurableObject<Env> {
	log(event: string, detail = 0): void {
		this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS log (seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, event TEXT, detail INTEGER)");
		this.ctx.storage.sql.exec("INSERT INTO log (at, event, detail) VALUES (?, ?, ?)", Date.now(), event, detail);
	}

	events(): { event: string; detail: number; at: number }[] {
		if (this.ctx.storage.sql.exec("SELECT 1 FROM sqlite_master WHERE name = 'log'").toArray().length === 0) return [];
		return this.ctx.storage.sql.exec<{ event: string; detail: number; at: number }>("SELECT event, detail, at FROM log ORDER BY seq").toArray();
	}

	async arm(mode: "ok" | "throw-once" | "work", delayMs: number): Promise<void> {
		await this.ctx.storage.put("mode", mode);
		await this.ctx.storage.setAlarm(Date.now() + delayMs);
	}

	async alarm(info?: AlarmInvocationInfo): Promise<void> {
		const mode = await this.ctx.storage.get<string>("mode");
		this.log("alarm", info?.retryCount ?? -1);
		if (mode === "throw-once" && !info?.isRetry) throw new Error("spike: first alarm attempt fails");
		if (mode === "work") {
			await scheduler.wait(300);
			this.log("work-done");
		}
	}

	// (d): the request schedules the work as an alarm, then stays open; the client disconnects while it waits.
	async fetch(): Promise<Response> {
		this.log("request-start");
		await this.arm("work", 0);
		await scheduler.wait(1500);
		this.log("request-end");
		return new Response("finished");
	}
}
