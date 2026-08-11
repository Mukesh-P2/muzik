import type { Room } from "./room.js";

const ROOM_KEY_PREFIX = "muzik:room:";

interface RedisRoomClient {
  keys(pattern: string): Promise<string[]>;
  mGet(keys: string[]): Promise<Array<string | null>>;
  set(key: string, value: string): Promise<unknown>;
  del(key: string): Promise<unknown>;
  readonly isOpen: boolean;
  close(): Promise<unknown>;
}

export interface RoomPersistence {
  loadRooms(): Promise<unknown[]>;
  saveRoom(room: Room): Promise<void>;
  deleteRoom(code: string): Promise<void>;
  close(): Promise<void>;
}

export async function connectRedisRoomPersistence(
  redisUrl: string | undefined,
): Promise<RoomPersistence | undefined> {
  const normalizedUrl = redisUrl?.trim();
  if (!normalizedUrl) return undefined;

  const { createClient } = await import("redis");
  const client = createClient({ url: normalizedUrl });
  client.on("error", (error) => {
    console.error("Redis room persistence error:", safeErrorMessage(error));
  });
  await client.connect();
  return new RedisRoomPersistence(client);
}

class RedisRoomPersistence implements RoomPersistence {
  constructor(private readonly client: RedisRoomClient) {}

  async loadRooms(): Promise<unknown[]> {
    const keys = await this.client.keys(`${ROOM_KEY_PREFIX}*`);
    if (keys.length === 0) return [];
    const values = await this.client.mGet(keys);
    const rooms: unknown[] = [];
    for (let index = 0; index < values.length; index += 1) {
      const value = values[index];
      if (typeof value !== "string") continue;
      try {
        rooms.push(JSON.parse(value));
      } catch {
        console.warn(`Ignoring malformed persisted room at ${keys[index] ?? "unknown key"}`);
      }
    }
    return rooms;
  }

  async saveRoom(room: Room): Promise<void> {
    await this.client.set(roomKey(room.code), JSON.stringify(room.toStoredState()));
  }

  async deleteRoom(code: string): Promise<void> {
    await this.client.del(roomKey(code));
  }

  async close(): Promise<void> {
    if (this.client.isOpen) await this.client.close();
  }
}

function roomKey(code: string): string {
  return `${ROOM_KEY_PREFIX}${code}`;
}

function safeErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Unknown Redis error";
}
