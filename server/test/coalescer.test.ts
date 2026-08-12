import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { KeyedTaskCoalescer, type ScheduledTask } from "../src/coalescer.js";

describe("keyed task coalescing", () => {
  it("runs at most one scheduled task per key until that task completes", () => {
    const scheduler = fakeScheduler();
    const runs: string[] = [];
    const coalescer = new KeyedTaskCoalescer(20, (key: string) => runs.push(key), scheduler.schedule);

    assert.equal(coalescer.schedule("ABC123"), true);
    assert.equal(coalescer.schedule("ABC123"), false);
    assert.equal(coalescer.schedule("XYZ789"), true);
    assert.deepEqual(scheduler.delays, [20, 20]);

    scheduler.runNext();
    assert.deepEqual(runs, ["ABC123"]);
    assert.equal(coalescer.schedule("ABC123"), true);

    scheduler.runAll();
    assert.deepEqual(runs, ["ABC123", "XYZ789", "ABC123"]);
  });

  it("cancels pending work by key or as a group", () => {
    const scheduler = fakeScheduler();
    const runs: string[] = [];
    const coalescer = new KeyedTaskCoalescer(20, (key: string) => runs.push(key), scheduler.schedule);
    coalescer.schedule("ABC123");
    coalescer.schedule("XYZ789");

    coalescer.cancel("ABC123");
    coalescer.cancelAll();
    scheduler.runAll();

    assert.deepEqual(runs, []);
  });
});

function fakeScheduler() {
  const tasks: Array<{ task: () => void; cancelled: boolean }> = [];
  const delays: number[] = [];
  const schedule: ScheduledTask = (task, delayMs) => {
    const scheduled = { task, cancelled: false };
    tasks.push(scheduled);
    delays.push(delayMs);
    return () => { scheduled.cancelled = true; };
  };
  const runNext = () => {
    const scheduled = tasks.shift();
    if (scheduled && !scheduled.cancelled) scheduled.task();
  };
  const runAll = () => {
    while (tasks.length > 0) runNext();
  };
  return { delays, runAll, runNext, schedule };
}
