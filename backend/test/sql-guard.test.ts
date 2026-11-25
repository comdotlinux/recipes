import { expect, it } from "vitest";

// SQL injection guard. Every statement passes its values as `?` bindings; the SQL text itself is fixed.
// The entries below are the only places where SQL text is assembled, and none uses request data:
// one picks between two fixed strings, the others quote a table name read from sqlite_master.
const ALLOWED_INTERPOLATIONS = [
	'DO UPDATE SET due_at = ${earliest ? "min(due_at, excluded.due_at)" : "excluded.due_at"}',
	'SELECT count(*) AS n FROM "${name}"',
	'DROP TABLE "${name}"',
];
// The only statement passed as a variable; it is a constant without interpolation.
const ALLOWED_VARIABLES = ["USER_TABLES"];

const SQL_CALL = /sql\.exec(?:<[^>(]*>)?\(\s*/g;
const endsStatement = (after: string) => /^\s*[,)]/.test(after);

/** SQL calls in `text` whose statement is not a fixed literal; `calls` counts every SQL call seen. */
function scan(text: string): { calls: number; offenders: string[] } {
	const offenders: string[] = [];
	let calls = 0;
	for (const call of text.matchAll(SQL_CALL)) {
		calls++;
		const rest = text.slice(call.index + call[0].length);
		const quote = rest[0];
		const where = rest.slice(0, 70).replace(/\s+/g, " ");
		if (quote === '"' || quote === "'" || quote === "`") {
			const end = rest.indexOf(quote, 1);
			const sql = rest.slice(1, end);
			const interpolated = quote === "`" && sql.includes("${") && !ALLOWED_INTERPOLATIONS.some((a) => sql.includes(a));
			// The literal must be the whole first argument: no `+` after it.
			if (interpolated || !endsStatement(rest.slice(end + 1))) offenders.push(where);
		} else if (!ALLOWED_VARIABLES.some((v) => rest.startsWith(`${v})`))) {
			offenders.push(where);
		}
	}
	return { calls, offenders };
}

it("no SQL statement in src/ is built from values", () => {
	const sources = import.meta.glob<string>("../src/**/*.ts", { query: "?raw", import: "default", eager: true });
	let calls = 0;
	const offenders: string[] = [];
	for (const [file, text] of Object.entries(sources)) {
		const found = scan(text);
		calls += found.calls;
		offenders.push(...found.offenders.map((o) => `${file}: ${o}`));
	}
	expect(offenders).toEqual([]);
	// The scan really saw the code: a wrong glob or pattern must not pass silently.
	expect(calls).toBeGreaterThan(40);
});

it("the guard flags interpolation, concatenation and a statement held in a variable, and accepts bindings", () => {
	expect(scan("this.sql.exec(`SELECT 1 FROM recipes WHERE id = '${id}'`)").offenders).toHaveLength(1);
	expect(scan('this.sql.exec("SELECT 1 FROM recipes WHERE id = " + id)').offenders).toHaveLength(1);
	expect(scan("this.sql.exec<Row>(query, id)").offenders).toHaveLength(1);
	expect(scan('this.sql.exec<Row>("SELECT 1 FROM recipes WHERE id = ?", id)')).toEqual({ calls: 1, offenders: [] });
});
