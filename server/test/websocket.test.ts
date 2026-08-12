import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { WebSocket } from "ws";
import { Room } from "../src/room.js";
import {
  MAX_OUTBOUND_BUFFERED_BYTES,
  sendJsonWithBackpressure,
} from "../src/websocket.js";

describe("outbound WebSocket buffering", () => {
  it("sends JSON while the socket is open and below the buffer cap", () => {
    const socket = fakeSocket();

    assert.equal(sendJsonWithBackpressure(socket, { type: "test", value: "ok" }), true);
    assert.deepEqual(socket.sent, ['{"type":"test","value":"ok"}']);
    assert.deepEqual(socket.closed, []);
  });

  it("closes an overloaded socket with a retryable status", () => {
    const socket = fakeSocket(MAX_OUTBOUND_BUFFERED_BYTES - 2);

    assert.equal(sendJsonWithBackpressure(socket, { type: "test" }), false);
    assert.deepEqual(socket.sent, []);
    assert.deepEqual(socket.closed, [{ code: 1013, reason: "Outbound buffer limit exceeded" }]);
  });

  it("allows the largest valid room state even when JSON escaping exceeds one MiB", () => {
    const room = new Room("ABC123");
    const host = room.addMember("\u0000".repeat(40), true);
    room.setConnected(host.id, true, 500);
    let videoIndex = 0;
    const nextVideo = () => ({
      videoId: (videoIndex++).toString(36).padStart(11, "0"),
      title: "\u0000".repeat(200),
      channelTitle: "\u0000".repeat(100),
      thumbnailUrl: "\u0000".repeat(500),
      durationMs: 86_400_000,
    });

    for (let index = 0; index < 51; index += 1) {
      const item = room.addToQueue(host.id, nextVideo());
      room.playQueueItem(host.id, item.id, 1_000 + index);
    }
    for (let index = 0; index < 100; index += 1) {
      room.addToQueue(host.id, nextVideo());
      room.sendChat(host.id, "\u0000".repeat(500), 10_000 + index * 1_000);
    }

    const payload = { type: "room_snapshot", room: room.publicStateFor(host.id) };
    const payloadBytes = Buffer.byteLength(JSON.stringify(payload), "utf8");
    assert.ok(payloadBytes > 1024 * 1024);
    assert.ok(payloadBytes < MAX_OUTBOUND_BUFFERED_BYTES);

    const socket = fakeSocket();
    assert.equal(sendJsonWithBackpressure(socket, payload), true);
    assert.equal(socket.closed.length, 0);
    assert.equal(socket.sent.length, 1);
  });

  it("ignores sockets that are no longer open", () => {
    const socket = fakeSocket(0, WebSocket.CLOSING);

    assert.equal(sendJsonWithBackpressure(socket, { type: "test" }), false);
    assert.deepEqual(socket.sent, []);
    assert.deepEqual(socket.closed, []);
  });
});

function fakeSocket(bufferedAmount = 0, readyState: number = WebSocket.OPEN) {
  const sent: string[] = [];
  const closed: Array<{ code: number; reason: string }> = [];
  return {
    readyState,
    bufferedAmount,
    sent,
    closed,
    send(data: string) {
      sent.push(data);
    },
    close(code: number, reason: string) {
      closed.push({ code, reason });
    },
  };
}
