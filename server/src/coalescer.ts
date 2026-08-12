export type ScheduledTask = (task: () => void, delayMs: number) => () => void;

export class KeyedTaskCoalescer<Key> {
  private readonly pending = new Map<Key, () => void>();

  constructor(
    private readonly delayMs: number,
    private readonly run: (key: Key) => void,
    private readonly scheduleTask: ScheduledTask = scheduleTimeout,
  ) {}

  schedule(key: Key): boolean {
    if (this.pending.has(key)) return false;
    const cancel = this.scheduleTask(() => {
      this.pending.delete(key);
      this.run(key);
    }, this.delayMs);
    this.pending.set(key, cancel);
    return true;
  }

  cancel(key: Key): void {
    this.pending.get(key)?.();
    this.pending.delete(key);
  }

  cancelAll(): void {
    for (const cancel of this.pending.values()) cancel();
    this.pending.clear();
  }
}

function scheduleTimeout(task: () => void, delayMs: number): () => void {
  const timer = setTimeout(task, delayMs);
  timer.unref();
  return () => clearTimeout(timer);
}
