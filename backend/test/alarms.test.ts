import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { migrate } from "../src/storage/migrations";
import { armAlarm, backoff, runDueTasks, schedule } from "../src/storage/tasks";

const stub = (name: string) => env.USER_DO.get(env.USER_DO.idFromName(name));
// Objects get their schema at the first session; these tests create it directly.
const withSchema = <R>(name: string, fn: (state: DurableObjectState) => Promise<R>) =>
	runInDurableObject(stub(name), async (_i, state) => {
		migrate(state.storage);
		return fn(state);
	});
const due = (state: DurableObjectState) =>
	state.storage.sql.exec<{ id: string; due_at: number; attempts: number }>("SELECT id, due_at, attempts FROM tasks ORDER BY id").toArray();

describe("alarm rules (plan 4.3)", () => {
	it("a later task does not delay an earlier one, in either insert order", async () => {
		await withSchema("order", async (state) => {
			const now = Date.now();
			await schedule(state.storage, { id: "late", kind: "k", dueAt: now + 600_000 });
			expect(await state.storage.getAlarm()).toBe(now + 600_000);
			await schedule(state.storage, { id: "early", kind: "k", dueAt: now + 60_000 });
			expect(await state.storage.getAlarm()).toBe(now + 60_000);
			await schedule(state.storage, { id: "later", kind: "k", dueAt: now + 900_000 });
			expect(await state.storage.getAlarm()).toBe(now + 60_000);
		});
	});

	it("schedule with earliest keeps the earlier due time of an existing task", async () => {
		await withSchema("earliest", async (state) => {
			const now = Date.now();
			await schedule(state.storage, { id: "x", kind: "k", dueAt: now + 1000 }, true);
			await schedule(state.storage, { id: "x", kind: "k", dueAt: now + 5000 }, true);
			expect(due(state)[0].due_at).toBe(now + 1000);
		});
	});

	it("a throwing task is retried alone with backoff; the other task runs once and is removed", async () => {
		await withSchema("isolation", async (state) => {
			const now = Date.now();
			const ran: string[] = [];
			await schedule(state.storage, { id: "ok", kind: "ok", dueAt: now - 2 });
			await schedule(state.storage, { id: "bad", kind: "bad", dueAt: now - 1 });
			await schedule(state.storage, { id: "future", kind: "ok", dueAt: now + 3_600_000 });
			const handlers = {
				ok: () => {
					ran.push("ok");
				},
				bad: () => {
					ran.push("bad");
					throw new Error("boom");
				},
			};
			expect(await runDueTasks(state.storage, handlers, now)).toEqual(["bad"]);
			expect(ran).toEqual(["ok", "bad"]);
			expect(due(state)).toEqual([
				{ id: "bad", due_at: now + backoff(1), attempts: 1 },
				{ id: "future", due_at: now + 3_600_000, attempts: 0 },
			]);
			expect(await state.storage.getAlarm()).toBe(now + backoff(1));
			// The retry runs only the failed task: "ok" is gone and "future" is not due.
			ran.length = 0;
			expect(await runDueTasks(state.storage, handlers, now + backoff(1))).toEqual(["bad"]);
			expect(ran).toEqual(["bad"]);
			expect(due(state)[0]).toEqual({ id: "bad", due_at: now + backoff(1) + backoff(2), attempts: 2 });
		});
	});

	it("a task of unknown kind counts as a failure and does not stop the others", async () => {
		await withSchema("unknown", async (state) => {
			const now = Date.now();
			await schedule(state.storage, { id: "u", kind: "nope", dueAt: now - 1 });
			expect(await runDueTasks(state.storage, {}, now)).toEqual(["u"]);
		});
	});

	it("backoff grows and is capped at one hour", () => {
		expect([1, 2, 3].map(backoff)).toEqual([30_000, 60_000, 120_000]);
		expect(backoff(20)).toBe(3_600_000);
	});

	it("with no tasks left the alarm is cleared", async () => {
		await withSchema("clear", async (state) => {
			await schedule(state.storage, { id: "a", kind: "ok", dueAt: Date.now() - 1 });
			await runDueTasks(state.storage, { ok: () => {} }, Date.now());
			expect(await state.storage.getAlarm()).toBeNull();
		});
	});

	it("repair on construction: a task row without its alarm gets the alarm back when the object loads", async () => {
		const name = "repair";
		const dueAt = Date.now() + 3_600_000;
		await withSchema(name, async (state) => {
			state.storage.sql.exec("INSERT INTO tasks (id, kind, due_at) VALUES ('orphan', 'expiry', ?)", dueAt);
			await state.storage.deleteAlarm();
			expect(await state.storage.getAlarm()).toBeNull();
		});
		await expect(runInDurableObject(stub(name), (_i, state) => state.abort("reload"))).rejects.toThrow();
		const alarm = await runInDurableObject(stub(name), (_i, state) => state.storage.getAlarm());
		expect(alarm).toBe(dueAt);
	});

	it("armAlarm leaves a correct alarm untouched", async () => {
		await withSchema("untouched", async (state) => {
			const at = Date.now() + 10_000;
			await schedule(state.storage, { id: "a", kind: "ok", dueAt: at });
			await armAlarm(state.storage);
			expect(await state.storage.getAlarm()).toBe(at);
		});
	});
});
