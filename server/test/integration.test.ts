import assert from "node:assert/strict";
import type { ChildProcess } from "node:child_process";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { after, before, describe, it } from "node:test";
import { WebSocket } from "ws";
import { MAX_INBOUND_MESSAGE_BYTES } from "../src/websocket.js";

interface Membership {
  roomCode: string;
  memberId: string;
  memberToken: string;
}

interface WireChatMessage {
  id: string;
  memberId: string;
  displayName: string;
  text: string;
  sentAt: number;
}

interface WireMessage {
  type?: string;
  message?: string | WireChatMessage;
  requestId?: string;
  addedCount?: number;
  startedPlayback?: boolean;
  room?: {
    me: { id: string; isHost: boolean };
    members: Array<{
      id: string;
      connected: boolean;
      isHost: boolean;
      songsAddedCount?: number;
      chatMuted?: boolean;
    }>;
    chat?: Array<{
      id: string;
      memberId: string;
      displayName: string;
      text: string;
      sentAt: number;
    }>;
    queue: Array<{
      id: string;
      video: { videoId: string; title: string };
      addedBy: string;
      addedByName?: string;
      voteCount: number;
      isForcedNext?: boolean;
    }>;
    playback: {
      status: string;
      video: { videoId: string; title: string } | null;
      revision: number;
      addedBy?: string;
      addedByName?: string;
    };
    history?: Array<{
      id: string;
      video: { videoId: string; title: string };
      addedBy: string;
      addedByName?: string;
      playedAt: number;
    }>;
    pauseVote?: {
      id: string;
      requestedBy: string;
      requestedByName: string;
      yesVotes: number;
      noVotes: number;
      threshold: number;
      eligibleVoters: number;
      myVote?: "yes" | "no";
      startedAt: number;
      expiresAt: number;
    };
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

  async waitForNext(
    predicate: (message: WireMessage) => boolean,
    timeoutMs = 5_000,
  ): Promise<WireMessage> {
    const firstUnseenIndex = this.history.length;
    return await new Promise<WireMessage>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.listeners.delete(check);
        reject(new Error(`Timed out waiting for next device message; received ${JSON.stringify(this.history.slice(firstUnseenIndex))}`));
      }, timeoutMs);
      const check = () => {
        const message = this.history
          .slice(firstUnseenIndex)
          .reverse()
          .find(predicate);
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
        env: {
          ...process.env,
          PORT: String(port),
          YOUTUBE_API_KEY: "",
          REDIS_URL: "",
        },
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
    const health = await fetch(`${baseUrl}/health`);
    const healthBody = await health.json() as {
      ok: boolean;
      uptimeSeconds: number;
      activeRooms: number;
      webSocketConnections: number;
    };
    assert.equal(healthBody.ok, true);
    assert.ok(healthBody.uptimeSeconds >= 0);
    assert.ok(healthBody.activeRooms >= 1);
    assert.equal(healthBody.webSocketConnections, 0);
    const metrics = await fetch(`${baseUrl}/metrics`);
    assert.match(await metrics.text(), /muzik_active_rooms [1-9]\d*/);
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

    const invalidPlaylist = await fetch(
      `${baseUrl}/api/youtube/playlist?value=${encodeURIComponent("not a playlist")}`,
      { headers: membershipHeaders(hostMembership) },
    );
    assert.equal(invalidPlaylist.status, 400);
    assert.deepEqual(await invalidPlaylist.json(), {
      error: "Enter a valid YouTube playlist URL or ID",
    });

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
      assert.equal(queued.room?.queue[0]?.addedBy, guestMembership.memberId);
      assert.equal(queued.room?.queue[0]?.addedByName, "Guest device");
      assert.equal(
        queued.room?.members.find((member) => member.id === guestMembership.memberId)
          ?.songsAddedCount,
        1,
      );

      host.send({ type: "queue_vote", itemId, enabled: true });
      await guest.waitFor((message) => message.room?.queue[0]?.voteCount === 2);

      host.send({ type: "playback_control", action: "play" });
      await guest.waitFor(
        (message) => message.room?.playback.video?.videoId === "dQw4w9WgXcQ" &&
          message.room.playback.status === "playing",
      );
      const attributed = await host.waitFor(
        (message) => message.room?.playback.addedBy === guestMembership.memberId,
      );
      assert.equal(attributed.room?.playback.addedByName, "Guest device");
      assert.deepEqual(attributed.room?.history, []);

      guest.send({ type: "chat_send", text: "Hello from integration" });
      const chatted = await host.waitFor((message) => {
        const chat = wireChatMessage(message);
        return message.type === "chat_message" &&
          chat?.text === "Hello from integration" &&
          chat.memberId === guestMembership.memberId;
      });
      assert.equal(chatted.room, undefined);
      const chatId = wireChatMessage(chatted)?.id;
      assert.ok(chatId);
      const legacyChatSnapshot = await host.waitFor(
        (message) => message.type === "room_snapshot" &&
          message.room?.chat?.some((chat) => chat.id === chatId) === true,
      );
      assert.equal(legacyChatSnapshot.room?.chat?.at(-1)?.text, "Hello from integration");

      host.send({ type: "chat_mute", memberId: guestMembership.memberId, muted: true });
      await guest.waitFor(
        (message) => message.room?.members.find(
          (member) => member.id === guestMembership.memberId,
        )?.chatMuted === true,
      );
      guest.send({ type: "chat_send", text: "Muted message" });
      await guest.waitFor(
        (message) => message.type === "error" &&
          message.message === "The host muted your room chat",
      );
      const deletedChat = guest.waitForNext(
        (message) => message.room?.chat?.length === 0,
      );
      host.send({ type: "chat_delete", messageId: chatId });
      await deletedChat;

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

  it("imports a playlist batch atomically and acknowledges the requester", async () => {
    const membership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Playlist host",
    });
    const host = await connectDevice(baseUrl, membership);
    try {
      await host.waitFor((message) => message.room?.me.id === membership.memberId);
      const resultPromise = host.waitForNext(
        (message) => message.type === "queue_import_result" &&
          message.requestId === "import-success",
      );
      host.send({
        type: "queue_add_many",
        requestId: "import-success",
        startPlayback: true,
        videos: [
          integrationVideo("eeeeeeeeeee", "Imported first"),
          integrationVideo("fffffffffff", "Imported second"),
        ],
      });
      const result = await resultPromise;
      assert.equal(result.addedCount, 2);
      assert.equal(result.startedPlayback, true);
      await host.waitFor(
        (message) => message.room?.playback.video?.videoId === "eeeeeeeeeee" &&
          message.room.queue.some((item) => item.video.videoId === "fffffffffff"),
      );

      const errorPromise = host.waitForNext(
        (message) => message.type === "error" &&
          message.requestId === "import-rejected",
      );
      host.send({
        type: "queue_add_many",
        requestId: "import-rejected",
        startPlayback: false,
        videos: [
          integrationVideo("ggggggggggg", "Must not be added"),
          integrationVideo("eeeeeeeeeee", "Duplicate current video"),
        ],
      });
      const rejected = await errorPromise;
      assert.equal(
        rejected.message,
        "Playlist contains a video that is already in this room",
      );

      const malformedErrorPromise = host.waitForNext(
        (message) => message.type === "error" &&
          message.requestId === "import-malformed",
      );
      host.send({
        type: "queue_add_many",
        requestId: "import-malformed",
        startPlayback: false,
        videos: [{
          ...integrationVideo("hhhhhhhhhhh", "Malformed metadata"),
          title: { nested: "not a string" },
        }],
      });
      const malformed = await malformedErrorPromise;
      assert.equal(malformed.message, "Valid YouTube video metadata is required");

      const snapshotPromise = host.waitForNext(
        (message) => message.type === "room_snapshot",
      );
      host.send({ type: "request_snapshot" });
      const snapshot = await snapshotPromise;
      assert.equal(
        snapshot.room?.queue.some((item) => item.video.videoId === "ggggggggggg"),
        false,
      );
      assert.equal(
        snapshot.room?.queue.some((item) => item.video.videoId === "hhhhhhhhhhh"),
        false,
      );
    } finally {
      await host.close();
    }
  });

  it("accepts a maximum-size valid playlist batch over the wire", async () => {
    const membership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Boundary host",
    });
    const host = await connectDevice(baseUrl, membership);
    try {
      await host.waitFor((message) => message.room?.me.id === membership.memberId);
      const videos = Array.from({ length: 50 }, (_, index) => ({
        videoId: index.toString(36).padStart(11, "0"),
        title: "\u0000".repeat(200),
        channelTitle: "\u0000".repeat(100),
        thumbnailUrl: "\u0000".repeat(500),
        durationMs: 86_400_000,
      }));
      const request = {
        type: "queue_add_many",
        requestId: "import-boundary",
        startPlayback: false,
        videos,
      };
      const requestBytes = Buffer.byteLength(JSON.stringify(request), "utf8");
      assert.ok(requestBytes > 64_000);
      assert.ok(requestBytes <= MAX_INBOUND_MESSAGE_BYTES);

      const resultPromise = host.waitForNext(
        (message) => message.type === "queue_import_result" &&
          message.requestId === "import-boundary",
      );
      host.send(request);
      assert.equal((await resultPromise).addedCount, 50);
      await host.waitFor((message) => message.room?.queue.length === 50);
    } finally {
      await host.close();
    }
  });

  it("bounds parallel WebSocket fan-out per room membership", async () => {
    const membership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Socket cap host",
    });
    const devices: TestDevice[] = [];
    try {
      for (let index = 0; index < 4; index += 1) {
        devices.push(await connectDevice(baseUrl, membership));
      }
      const extraSocket = new WebSocket(
        baseUrl.replace("http://", "ws://") + "/ws",
        { headers: membershipHeaders(membership) },
      );
      const closed = new Promise<{ code: number; reason: string }>((resolve, reject) => {
        extraSocket.once("close", (code, reason) => resolve({
          code,
          reason: reason.toString(),
        }));
        extraSocket.once("error", reject);
      });
      const result = await closed;
      assert.deepEqual(result, {
        code: 1008,
        reason: "Too many connections for this room member",
      });
    } finally {
      await Promise.all(devices.map(async (device) => await device.close()));
    }
  });

  it("broadcasts reorder, forced-next, history, and pause votes across clients", async () => {
    const hostMembership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Host",
    });
    const firstMembership = await postMembership(
      `${baseUrl}/api/rooms/${hostMembership.roomCode}/join`,
      { displayName: "First guest" },
    );
    const secondMembership = await postMembership(
      `${baseUrl}/api/rooms/${hostMembership.roomCode}/join`,
      { displayName: "Second guest" },
    );
    const host = await connectDevice(baseUrl, hostMembership);
    const first = await connectDevice(baseUrl, firstMembership);
    const second = await connectDevice(baseUrl, secondMembership);

    try {
      await second.waitFor((message) => connectedCount(message) === 3);
      host.send({
        type: "queue_add",
        video: integrationVideo("aaaaaaaaaaa", "Current"),
      });
      const currentSnapshot = await first.waitFor(
        (message) => message.room?.queue.some((item) => item.video.title === "Current") === true,
      );
      const currentId = currentSnapshot.room?.queue.find(
        (item) => item.video.title === "Current",
      )?.id;
      assert.ok(currentId);
      host.send({ type: "play_item", itemId: currentId });
      await second.waitFor(
        (message) => message.room?.playback.video?.title === "Current" &&
          message.room.playback.status === "playing",
      );

      first.send({
        type: "queue_add",
        video: integrationVideo("bbbbbbbbbbb", "First tied"),
      });
      await second.waitFor((message) => message.room?.queue.length === 1);
      second.send({
        type: "queue_add",
        video: integrationVideo("ccccccccccc", "Second tied"),
      });
      await host.waitFor((message) => message.room?.queue.length === 2);
      first.send({
        type: "queue_add",
        video: integrationVideo("ddddddddddd", "Popular"),
      });
      const threeQueued = await second.waitFor((message) => message.room?.queue.length === 3);
      const firstTiedId = threeQueued.room?.queue.find(
        (item) => item.video.title === "First tied",
      )?.id;
      const secondTiedId = threeQueued.room?.queue.find(
        (item) => item.video.title === "Second tied",
      )?.id;
      const popularId = threeQueued.room?.queue.find(
        (item) => item.video.title === "Popular",
      )?.id;
      assert.ok(firstTiedId && secondTiedId && popularId);

      second.send({ type: "queue_vote", itemId: popularId, enabled: true });
      await host.waitFor(
        (message) => message.room?.queue.find((item) => item.id === popularId)?.voteCount === 2,
      );
      first.send({
        type: "queue_reorder",
        itemId: secondTiedId,
        beforeItemId: firstTiedId,
      });
      await first.waitFor(
        (message) => message.type === "error" && message.message === "Host permission required",
      );

      host.send({
        type: "queue_reorder",
        itemId: secondTiedId,
        beforeItemId: firstTiedId,
      });
      const reordered = await second.waitFor(
        (message) => message.room?.queue.map((item) => item.video.title).join(",") ===
          "Popular,Second tied,First tied",
      );
      assert.deepEqual(
        reordered.room?.queue.map((item) => item.id),
        [popularId, secondTiedId, firstTiedId],
      );
      host.send({
        type: "queue_reorder",
        itemId: secondTiedId,
        beforeItemId: popularId,
      });
      await host.waitFor(
        (message) => message.type === "error" &&
          message.message === "Only queue items with equal votes can be reordered",
      );

      host.send({ type: "queue_play_next", itemId: firstTiedId });
      const markedForHost = await host.waitFor(
        (message) => message.room?.queue.find((item) => item.id === firstTiedId)
          ?.isForcedNext === true,
      );
      const markedForGuest = await first.waitFor(
        (message) => message.room?.queue.find((item) => item.id === firstTiedId)
          ?.isForcedNext === true,
      );
      assert.equal(markedForHost.room?.playback.video?.title, "Current");
      assert.equal(markedForGuest.room?.playback.video?.title, "Current");

      host.send({ type: "playback_control", action: "next" });
      const forcedPlayed = await second.waitFor(
        (message) => message.room?.playback.video?.title === "First tied",
      );
      assert.equal(forcedPlayed.room?.playback.addedBy, firstMembership.memberId);
      assert.equal(forcedPlayed.room?.playback.addedByName, "First guest");
      assert.deepEqual(
        forcedPlayed.room?.history?.map((item) => item.video.title),
        ["Current"],
      );
      assert.equal(
        forcedPlayed.room?.queue.some((item) => item.isForcedNext),
        false,
      );

      first.send({ type: "pause_request" });
      const requested = await host.waitFor(
        (message) => message.room?.pauseVote?.requestedBy === firstMembership.memberId,
      );
      assert.equal(requested.room?.pauseVote?.yesVotes, 0);
      assert.equal(requested.room?.pauseVote?.threshold, 2);
      assert.equal(requested.room?.pauseVote?.eligibleVoters, 3);
      const pollId = requested.room?.pauseVote?.id;
      assert.ok(pollId);
      first.send({ type: "pause_vote", vote: "no", pollId });
      await second.waitFor((message) => message.room?.pauseVote?.noVotes === 1);
      second.send({ type: "pause_vote", vote: "yes", pollId });
      await host.waitFor((message) => message.room?.pauseVote?.yesVotes === 1);
      first.send({ type: "pause_vote", vote: "yes", pollId });
      const votePaused = await second.waitFor(
        (message) => message.room?.playback.status === "paused" &&
          message.room.pauseVote === undefined,
      );
      assert.equal(votePaused.room?.playback.video?.title, "First tied");

      host.send({ type: "playback_control", action: "play" });
      const resumed = await first.waitFor(
        (message) => message.room?.playback.status === "playing" &&
          message.room.playback.video?.title === "First tied",
      );
      const resumedRevision = resumed.room?.playback.revision ?? 0;
      host.send({ type: "playback_control", action: "pause" });
      await first.waitFor(
        (message) => message.room?.playback.status === "paused" &&
          message.room.playback.revision > resumedRevision,
      );
    } finally {
      await host.close();
      await first.close();
      await second.close();
    }
  });

  it("excludes connected legacy clients from pause-vote thresholds", async () => {
    const hostMembership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Host",
    });
    const requesterMembership = await postMembership(
      `${baseUrl}/api/rooms/${hostMembership.roomCode}/join`,
      { displayName: "Requester" },
    );
    const legacyMembership = await postMembership(
      `${baseUrl}/api/rooms/${hostMembership.roomCode}/join`,
      { displayName: "Legacy client" },
    );
    const host = await connectDevice(baseUrl, hostMembership);
    const requester = await connectDevice(baseUrl, requesterMembership);
    const legacy = await connectDevice(baseUrl, legacyMembership, false);

    try {
      await requester.waitFor((message) => connectedCount(message) === 3);
      host.send({
        type: "queue_add",
        video: integrationVideo("ggggggggggg", "Mixed-version current"),
      });
      const queued = await requester.waitFor((message) => message.room?.queue.length === 1);
      const itemId = queued.room?.queue[0]?.id;
      assert.ok(itemId);
      host.send({ type: "play_item", itemId });
      await requester.waitFor(
        (message) => message.room?.playback.status === "playing",
      );

      requester.send({ type: "pause_request" });
      const requested = await requester.waitFor(
        (message) => message.room?.pauseVote?.requestedBy === requesterMembership.memberId,
      );
      assert.equal(requested.room?.pauseVote?.eligibleVoters, 2);
      assert.equal(requested.room?.pauseVote?.threshold, 1);
      const pollId = requested.room?.pauseVote?.id;
      assert.ok(pollId);
      requester.send({ type: "pause_vote", vote: "yes", pollId });
      await legacy.waitFor(
        (message) => message.room?.playback.status === "paused" &&
          message.room.pauseVote === undefined,
      );
    } finally {
      await host.close();
      await requester.close();
      await legacy.close();
    }
  });

  it("broadcasts each pause-poll completion path", async () => {
    const autoHostMembership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Auto host",
    });
    const autoGuestMembership = await postMembership(
      `${baseUrl}/api/rooms/${autoHostMembership.roomCode}/join`,
      { displayName: "Auto requester" },
    );
    const continueHostMembership = await postMembership(`${baseUrl}/api/rooms`, {
      displayName: "Continue host",
    });
    const continueRequesterMembership = await postMembership(
      `${baseUrl}/api/rooms/${continueHostMembership.roomCode}/join`,
      { displayName: "Continue requester" },
    );
    const continueOtherMembership = await postMembership(
      `${baseUrl}/api/rooms/${continueHostMembership.roomCode}/join`,
      { displayName: "Continue other" },
    );

    const autoHost = await connectDevice(baseUrl, autoHostMembership);
    const autoGuest = await connectDevice(baseUrl, autoGuestMembership);
    const continueHost = await connectDevice(baseUrl, continueHostMembership);
    const continueRequester = await connectDevice(baseUrl, continueRequesterMembership);
    const continueOther = await connectDevice(baseUrl, continueOtherMembership);

    try {
      await autoGuest.waitFor((message) => connectedCount(message) === 2);
      await continueOther.waitFor((message) => connectedCount(message) === 3);

      autoHost.send({
        type: "queue_add",
        video: integrationVideo("eeeeeeeeeee", "Auto-pause current"),
      });
      const autoQueued = await autoGuest.waitFor(
        (message) => message.room?.queue.some(
          (item) => item.video.title === "Auto-pause current",
        ) === true,
      );
      const autoItemId = autoQueued.room?.queue.find(
        (item) => item.video.title === "Auto-pause current",
      )?.id;
      assert.ok(autoItemId);
      autoHost.send({ type: "play_item", itemId: autoItemId });
      const autoPlaying = await autoGuest.waitFor(
        (message) => message.room?.playback.video?.title === "Auto-pause current" &&
          message.room.playback.status === "playing",
      );
      const autoPlayingRevision = autoPlaying.room?.playback.revision ?? 0;

      continueHost.send({
        type: "queue_add",
        video: integrationVideo("fffffffffff", "Continue current"),
      });
      const continueQueued = await continueRequester.waitFor(
        (message) => message.room?.queue.some(
          (item) => item.video.title === "Continue current",
        ) === true,
      );
      const continueItemId = continueQueued.room?.queue.find(
        (item) => item.video.title === "Continue current",
      )?.id;
      assert.ok(continueItemId);
      continueHost.send({ type: "play_item", itemId: continueItemId });
      const continuePlaying = await continueOther.waitFor(
        (message) => message.room?.playback.video?.title === "Continue current" &&
          message.room.playback.status === "playing",
      );
      const continuePlayingRevision = continuePlaying.room?.playback.revision ?? 0;

      const allVotedRequestedPromise = continueHost.waitForNext(
        (message) => message.room?.pauseVote?.requestedBy ===
          continueRequesterMembership.memberId,
      );
      continueRequester.send({ type: "pause_request" });
      const allVotedRequested = await allVotedRequestedPromise;
      const allVotedPollId = allVotedRequested.room?.pauseVote?.id;
      assert.ok(allVotedPollId);
      assert.equal(
        (allVotedRequested.room?.pauseVote?.expiresAt ?? 0) -
          (allVotedRequested.room?.pauseVote?.startedAt ?? 0),
        10_000,
      );

      const firstNoPromise = continueOther.waitForNext(
        (message) => message.room?.pauseVote?.noVotes === 1,
      );
      continueRequester.send({ type: "pause_vote", vote: "no", pollId: allVotedPollId });
      await firstNoPromise;
      const secondNoPromise = continueHost.waitForNext(
        (message) => message.room?.pauseVote?.noVotes === 2,
      );
      continueOther.send({ type: "pause_vote", vote: "no", pollId: allVotedPollId });
      await secondNoPromise;
      const allVotedClosedPromise = continueRequester.waitForNext(
        (message) => message.room?.playback.status === "playing" &&
          message.room.playback.revision === continuePlayingRevision &&
          message.room.pauseVote === undefined,
      );
      continueHost.send({ type: "pause_vote", vote: "no", pollId: allVotedPollId });
      await allVotedClosedPromise;

      const autoRequestedPromise = autoHost.waitForNext(
        (message) => message.room?.pauseVote?.requestedBy === autoGuestMembership.memberId,
      );
      autoGuest.send({ type: "pause_request" });
      const autoRequested = await autoRequestedPromise;
      assert.equal(
        (autoRequested.room?.pauseVote?.expiresAt ?? 0) -
          (autoRequested.room?.pauseVote?.startedAt ?? 0),
        10_000,
      );
      const autoPausedPromise = autoHost.waitForNext(
        (message) => message.room?.playback.status === "paused" &&
          message.room.playback.revision > autoPlayingRevision &&
          message.room.pauseVote === undefined,
        15_000,
      );

      const partialRequestedPromise = continueHost.waitForNext(
        (message) => message.room?.pauseVote?.requestedBy ===
          continueRequesterMembership.memberId,
      );
      continueRequester.send({ type: "pause_request" });
      const partialRequested = await partialRequestedPromise;
      const partialPollId = partialRequested.room?.pauseVote?.id;
      assert.ok(partialPollId);
      assert.equal(
        (partialRequested.room?.pauseVote?.expiresAt ?? 0) -
          (partialRequested.room?.pauseVote?.startedAt ?? 0),
        10_000,
      );
      const partialVotePromise = continueOther.waitForNext(
        (message) => message.room?.pauseVote?.yesVotes === 1,
      );
      continueRequester.send({ type: "pause_vote", vote: "yes", pollId: partialPollId });
      await partialVotePromise;
      const partialContinuedPromise = continueHost.waitForNext(
        (message) => message.room?.playback.status === "playing" &&
          message.room.playback.revision === continuePlayingRevision &&
          message.room.pauseVote === undefined,
        15_000,
      );

      const [autoPaused, partialContinued] = await Promise.all([
        autoPausedPromise,
        partialContinuedPromise,
      ]);
      assert.equal(autoPaused.room?.playback.video?.title, "Auto-pause current");
      assert.equal(partialContinued.room?.playback.video?.title, "Continue current");
    } finally {
      await autoHost.close();
      await autoGuest.close();
      await continueHost.close();
      await continueRequester.close();
      await continueOther.close();
    }
  });
});

function integrationVideo(videoId: string, title: string) {
  return {
    videoId,
    title,
    channelTitle: "Integration channel",
    thumbnailUrl: "https://example.test/thumbnail.jpg",
  };
}

function connectedCount(message: WireMessage): number | undefined {
  return message.room?.members.filter((member) => member.connected).length;
}

function wireChatMessage(message: WireMessage): WireChatMessage | undefined {
  return typeof message.message === "object" ? message.message : undefined;
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

async function connectDevice(
  baseUrl: string,
  membership: Membership,
  pauseVoteCapable = true,
): Promise<TestDevice> {
  const headers = membershipHeaders(membership);
  if (pauseVoteCapable) headers["X-Muzik-Capabilities"] = "pause-vote-v1";
  const socket = new WebSocket(baseUrl.replace("http://", "ws://") + "/ws", {
    headers,
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
