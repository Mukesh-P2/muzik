import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Room, RoomError, RoomManager } from "../src/room.js";
import type { VideoSummary } from "../src/types.js";

const video = (videoId: string, title: string): VideoSummary => ({
  videoId,
  title,
  channelTitle: "Test channel",
  thumbnailUrl: "https://example.test/thumb.jpg",
});

describe("Room", () => {
  it("orders the queue by votes and starts with a lead time", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    room.setConnected(host.id, true);
    room.setConnected(guest.id, true);

    const first = room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    const second = room.addToQueue(guest.id, video("bbbbbbbbbbb", "Second"));
    room.setVote(host.id, second.id, true);

    const state = room.publicStateFor(host.id, 1_000);
    assert.equal(state.queue[0]?.id, second.id);
    room.control(host.id, "play", undefined, 2_000);
    assert.equal(room.playback.video?.title, "Second");
    assert.equal(room.playback.anchorServerTimeMs, 3_200);
    assert.equal(room.queue.has(first.id), true);
  });

  it("rejects playback control from a non-host", () => {
    const room = new Room("ABC123");
    room.addMember("Host", true);
    const guest = room.addMember("Guest");
    assert.throws(
      () => room.control(guest.id, "play", undefined),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
  });

  it("hands host permission to the earliest connected member", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const firstGuest = room.addMember("First guest");
    const secondGuest = room.addMember("Second guest");
    room.setConnected(host.id, true);
    room.setConnected(firstGuest.id, true);
    room.setConnected(secondGuest.id, true);
    room.setConnected(host.id, false);
    assert.equal(firstGuest.isHost, true);
    assert.equal(secondGuest.isHost, false);
  });

  it("elects a connected guest when the creator never connects", () => {
    const room = new Room("ABC123");
    const creator = room.addMember("Creator", true);
    const guest = room.addMember("Guest");

    room.setConnected(guest.id, true);

    assert.equal(creator.isHost, false);
    assert.equal(guest.isHost, true);
  });

  it("skips when half of connected members vote", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    room.setConnected(host.id, true);
    room.setConnected(guest.id, true);
    room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    room.addToQueue(guest.id, video("bbbbbbbbbbb", "Second"));
    room.control(host.id, "play", undefined, 1_000);
    assert.equal(room.voteToSkip(guest.id, 3_000), true);
    assert.equal(room.playback.video?.title, "Second");
  });

  it("anchors pause and seek controls in server time", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    room.setConnected(host.id, true);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.control(host.id, "pause", undefined, 4_000);
    assert.equal(room.playback.status, "paused");
    assert.equal(room.playback.anchorServerTimeMs, 4_500);
    assert.equal(room.playback.positionMs, 2_300);

    room.control(host.id, "seek", 42_123, 5_000);
    assert.equal(room.playback.positionMs, 42_123);
    assert.equal(room.playback.anchorServerTimeMs, 5_500);

    assert.throws(
      () => room.control(host.id, "seek", 86_400_001, 6_000),
      (error) => error instanceof RoomError && error.statusCode === 400,
    );
  });

  it("validates videos and rejects duplicate queue entries", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    assert.throws(
      () => room.addToQueue(host.id, video("short", "Invalid")),
      (error) => error instanceof RoomError && error.statusCode === 400,
    );
    room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    assert.throws(
      () => room.addToQueue(host.id, video("aaaaaaaaaaa", "Duplicate")),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );
  });

  it("removes a disconnected member's skip vote", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const first = room.addMember("First");
    const second = room.addMember("Second");
    room.setConnected(host.id, true);
    room.setConnected(first.id, true);
    room.setConnected(second.id, true);
    room.voteToSkip(first.id, 1_000);

    room.setConnected(first.id, false);

    assert.equal(room.skipVotes.has(first.id), false);
    assert.equal(room.publicStateFor(host.id).skip.votes, 0);
  });

  it("removes a departing member's votes and transfers host permission", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    const secondGuest = room.addMember("Second guest");
    room.setConnected(host.id, true);
    room.setConnected(guest.id, true);
    room.setConnected(secondGuest.id, true);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    room.setVote(guest.id, item.id, true);
    room.voteToSkip(host.id);

    room.removeMember(host.id);

    assert.equal(room.hasMember(host.id), false);
    assert.equal(room.publicStateFor(guest.id).me.isHost, true);
    assert.equal(room.publicStateFor(guest.id).queue[0]?.voteCount, 1);
    assert.equal(room.publicStateFor(guest.id).skip.votes, 0);
  });
});

describe("RoomManager", () => {
  it("prunes only inactive rooms without connected members", () => {
    const rooms = new RoomManager();
    const inactive = rooms.createRoom("Inactive");
    const active = rooms.createRoom("Active");
    active.room.setConnected(active.member.id, true);
    inactive.room.lastActivityAt = 1_000;
    active.room.lastActivityAt = 1_000;

    assert.deepEqual(rooms.pruneInactiveRooms(5_000, 10_000), [inactive.room.code]);
    assert.throws(() => rooms.getRoom(inactive.room.code), RoomError);
    assert.equal(rooms.getRoom(active.room.code), active.room);
  });
});
