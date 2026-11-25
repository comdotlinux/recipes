/**
 * The single alarm per object serves every task kind (plan 4.3, "Alarm rules"):
 * - after every insert and every run the alarm is set to the earliest due_at, because setAlarm replaces the pending one;
 * - the handler never throws; each task runs in its own try/catch, and a failure reschedules only that task with backoff.
 */
/** `attempts` = how many earlier runs of this task failed (0 on the first run). */
export type TaskHandler = (payload: string | null, now: number, attempts: number) => void | Promise<void>;

type TaskRow = { id: string; kind: string; due_at: number; attempts: number; payload: string | null };

const MAX_BACKOFF_MS = 60 * 60 * 1000;

export const backoff = (attempts: number) => Math.min(30_000 * 2 ** (attempts - 1), MAX_BACKOFF_MS);

export async function armAlarm(storage: DurableObjectStorage): Promise<void> {
	const next = storage.sql.exec<{ due: number | null }>("SELECT min(due_at) AS due FROM tasks").one().due;
	if (next === null) await storage.deleteAlarm();
	else if ((await storage.getAlarm()) !== next) await storage.setAlarm(next);
}

/** Inserts or moves a task. With `earliest`, an existing task with the same id keeps the earlier of both due times. */
export async function schedule(
	storage: DurableObjectStorage,
	task: { id: string; kind: string; dueAt: number; payload?: string | null },
	earliest = false,
): Promise<void> {
	storage.sql.exec(
		`INSERT INTO tasks (id, kind, due_at, attempts, payload) VALUES (?, ?, ?, 0, ?)
		 ON CONFLICT (id) DO UPDATE SET due_at = ${earliest ? "min(due_at, excluded.due_at)" : "excluded.due_at"}, payload = excluded.payload`,
		task.id,
		task.kind,
		task.dueAt,
		task.payload ?? null,
	);
	await armAlarm(storage);
}

/** Runs every due task once. Never throws. Returns the ids that failed. */
export async function runDueTasks(storage: DurableObjectStorage, handlers: Record<string, TaskHandler>, now: number): Promise<string[]> {
	const due = storage.sql.exec<TaskRow>("SELECT id, kind, due_at, attempts, payload FROM tasks WHERE due_at <= ? ORDER BY due_at", now).toArray();
	const failed: string[] = [];
	for (const task of due) {
		// Count the try before running it, and make that durable: a handler killed by the runtime (CPU limit, eviction)
		// never reaches the catch below, and must still use up a try so a job cannot stay "running" forever.
		const attempts = task.attempts + 1;
		storage.sql.exec("UPDATE tasks SET attempts = ? WHERE id = ?", attempts, task.id);
		await storage.sync();
		try {
			const handler = handlers[task.kind];
			if (!handler) throw new Error(`no handler for task kind ${task.kind}`);
			await handler(task.payload, now, task.attempts);
			// A handler may have rescheduled its own task to a later time; only remove it if it is still the run that was due.
			storage.sql.exec("DELETE FROM tasks WHERE id = ? AND due_at = ?", task.id, task.due_at);
		} catch (e) {
			failed.push(task.id);
			storage.sql.exec("UPDATE tasks SET due_at = ? WHERE id = ?", now + backoff(attempts), task.id);
			// Name only: an error from a model call can carry user text in its message.
			console.error(`task ${task.id} (${task.kind}) failed, attempt ${attempts}: ${e instanceof Error ? e.name : "non-error value"}`);
		}
	}
	await armAlarm(storage);
	return failed;
}
