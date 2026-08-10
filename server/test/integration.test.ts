import assert from "node:assert/strict";
import type { ChildProcess } from "node:child_process";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { after, before, describe, it } from "node:test";
import { WebSocket } from "ws";

interface Membership {
  roomCode: string;
  memberId: string;
  memberToken: string;
}

interface WireMessage {
  type?: string;
  message?: string;
  room?: {
    me: { id: string; isHost: boolean };
    members: Array<{ id: string; connected: boolean; isHost: boolean }>;
    queue: Array<{ id: string; voteCount: number }>;
    playback: { status: string; video: { videoId: string } | null };
  };
}

class TestDevice {
  private readonly history: WireMessage[] = [];
  private readonly listeners = new Set<() => void>();

  constructor(readonly socket: WebSocket) {
    socket.on("message", (data) => {
      this.history.push(JSON.parse(data.toString()) as WireMessage);
      for (const listener of this.listeners) listener();
    });
  }

  send(value: unknown): void {
    this.socket.send(JSON.stringify(value));
  }

  async waitFor(predicate: (message: WireMessage) => boolean): Promise<WireMessage> {
    const existing = [...this.history].reverse().find(predicate);
    if (existing) return existing;

    return await new Promise<WireMessage>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.listeners.delete(check);
        reject(new Error(`Timed out waiting for device message; received ${JSON.stringify(this.history)}`));
      }, 5_000);
      const check = () => {
        const message = [...this.history].reverse().find(predicate);
        if (!message) return;
        clearTimeout(timeout);
        this.listeners.delete(check);
        resolve(message);
      };
      this.listeners.add(check);
    });
  }

  async close(): Promise<void> {
    if (this.socket.readyState === WebSocket.CLOSED) return;
    const closed = new Promise<void>((resolve) => this.socket.once("close", () => resolve()));
    this.socket.close();
    await closed;
  }
}

describe("HTTP and WebSocket integration", () => {
  let serverProcess: ChildProcess;
  let baseUrl: string;
  let processOutput = "";

  before(async () => {
    const port = await availablePort();
    baseUrl = `http://127.0.0.1:${port}`;
    serverProcess = spawn(
      process.execPath,
      ["--import", "tsx", "src/index.ts"],
      {
        cwd: process.cwd(),
        env: { ...process.env, PORT: String(port), YOUTUBE_API_KEY: "" },
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    serverProcess.stdout?.on("data", (chunk) => { processOutput += chunk.toString(); });
    serverProcess.stderr?.on("data", (chunk) => { processOutput += chunk.toString(); });
    await waitUntilHealthy(baseUrl, () => processOutput);
  });

  after(async () => {
    if (!serverProcess || serverProcess.exitCode !== null) return;
    const exited = new Promise<void>((resolve) => serverProcess.once("exit", () => resolve()));
    serverProcess.kill("SIGTERM");
    await exited;
  });

  it("synchronizes two clients, reconnects, and hands off host permission", async () => {
    const invalidResponse = await fetch(`${baseUrl}/api/rooms`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "null",
    });
    assert.equal(invalidResponse.status, 400);
    assert.deepEqual(await invalidResponse.json(), { error: "Invalid JSON body" });

    const hostMembership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Host device",
    });
    const guestMembership = await postMembership(
      `${baseUrl}/api/rooms/${hostMembership.roomCode}/join`,
      { displayName: "Guest device" },
    );

    const longSearch = await fetch(
      `${baseUrl}/api/youtube/search?q=${"a".repeat(101)}`,
      {
        headers: membershipHeaders(hostMembership),
      },
    );
    assert.equal(longSearch.status, 400);
    assert.deepEqual(await longSearch.json(), { error: "Search query is too long" });

    const host = await connectDevice(baseUrl, hostMembership);
    const guest = await connectDevice(baseUrl, guestMembership);
    try {
      await host.waitFor((message) => connectedCount(message) === 2);
      await guest.waitFor((message) => connectedCount(message) === 2);

      guest.send({
        type: "queue_add",
        video: {
          videoId: "dQw4w9WgXcQ",
          title: "Integration test video",
          channelTitle: "Test channel",
          thumbnailUrl: "https://example.test/thumbnail.jpg",
        },
      });
      const queued = await host.waitFor((message) => message.room?.queue.length === 1);
      const itemId = queued.room?.queue[0]?.id;
      assert.ok(itemId);

      host.send({ type: "queue_vote", itemId, enabled: true });
      await guest.waitFor((message) => message.room?.queue[0]?.voteCount === 2);

      host.send({ type: "playback_control", action: "play" });
      await guest.waitFor(
        (message) => message.room?.playback.video?.videoId === "dQw4w9WgXcQ" &&
          message.room.playback.status === "playing",
      );

      guest.send(null);
      const invalidMessage = await guest.waitFor(
        (message) => message.type === "error" && message.message === "Invalid room message",
      );
      assert.equal(invalidMessage.message, "Invalid room message");

      await guest.close();
      await host.waitFor((message) => connectedCount(message) === 1);

      const reconnectedGuest = await connectDevice(baseUrl, guestMembership);
      try {
        await reconnectedGuest.waitFor((message) => connectedCount(message) === 2);
        await host.close();
        const handedOff = await reconnectedGuest.waitFor(
          (message) => connectedCount(message) === 1 && message.room?.me.isHost === true,
        );
        assert.equal(handedOff.room?.me.id, guestMembership.memberId);

        reconnectedGuest.send({ type: "leave_room" });
        await waitForSocketClose(reconnectedGuest.socket);
        await assert.rejects(connectDevice(baseUrl, guestMembership));
      } finally {
        await reconnectedGuest.close();
      }
    } finally {
      await host.close();
      await guest.close();
    }
  });
});

function connectedCount(message: WireMessage): number | undefined {
  return message.room?.members.filter((member) => member.connected).length;
}

async function postMembership(url: string, body: unknown): Promise<Membership> {
  const response = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const responseBody = await response.text();
  assert.equal(response.status, 201, responseBody);
  return JSON.parse(responseBody) as Membership;
}

async function connectDevice(baseUrl: string, membership: Membership): Promise<TestDevice> {
  const socket = new WebSocket(baseUrl.replace("http://", "ws://") + "/ws", {
    headers: membershipHeaders(membership),
  });
  const device = new TestDevice(socket);
  await new Promise<void>((resolve, reject) => {
    socket.once("open", () => resolve());
    socket.once("error", reject);
  });
  return device;
}

function membershipHeaders(membership: Membership): Record<string, string> {
  return {
    "X-Room-Code": membership.roomCode,
    "X-Member-Id": membership.memberId,
    "X-Member-Token": membership.memberToken,
  };
}

async function waitForSocketClose(socket: WebSocket): Promise<void> {
  if (socket.readyState === WebSocket.CLOSED) return;
  await new Promise<void>((resolve) => socket.once("close", () => resolve()));
}

async function availablePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return address.port;
}

async function waitUntilHealthy(baseUrl: string, output: () => string): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.ok) return;
    } catch {
      // The child process is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Server did not become healthy. Output: ${output()}`);
}
