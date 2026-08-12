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

function enablePauseVoting(
  room: Room,
  members: Array<{ id: string }>,
  now = 500,
): void {
  for (const member of members) {
    room.setConnected(member.id, true, now);
    room.setPauseVoteCapable(member.id, true, now);
  }
}

function currentPausePollId(room: Room, memberId: string, now: number): string {
  const pollId = room.publicStateFor(memberId, now).pauseVote?.id;
  assert.ok(pollId);
  return pollId;
}

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

  it("lets the host reorder only within an equal-vote tier", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    const first = room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    const second = room.addToQueue(host.id, video("bbbbbbbbbbb", "Second"));
    const third = room.addToQueue(guest.id, video("ccccccccccc", "Third"));
    const popular = room.addToQueue(guest.id, video("ddddddddddd", "Popular"));
    room.setVote(host.id, popular.id, true);

    assert.deepEqual(
      room.publicStateFor(host.id).queue.map((item) => item.id),
      [popular.id, first.id, second.id, third.id],
    );
    assert.throws(
      () => room.reorderQueueItem(guest.id, third.id, first.id),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );

    room.reorderQueueItem(host.id, third.id, first.id);
    assert.deepEqual(
      room.publicStateFor(host.id).queue.map((item) => item.id),
      [popular.id, third.id, first.id, second.id],
    );
    room.reorderQueueItem(host.id, first.id, null);
    assert.deepEqual(
      room.publicStateFor(host.id).queue.map((item) => item.id),
      [popular.id, third.id, second.id, first.id],
    );
    assert.throws(
      () => room.reorderQueueItem(host.id, first.id, popular.id),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );

    room.setVote(guest.id, first.id, true);
    room.reorderQueueItem(host.id, popular.id, first.id);
    assert.deepEqual(
      room.publicStateFor(host.id).queue.map((item) => item.id),
      [popular.id, first.id, third.id, second.id],
    );
    room.setVote(guest.id, first.id, false);
    assert.deepEqual(
      room.publicStateFor(host.id).queue.map((item) => item.id),
      [popular.id, third.id, second.id, first.id],
    );
  });

  it("forces one queued item for the next transition without interrupting playback", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    const current = room.addToQueue(guest.id, video("aaaaaaaaaaa", "Current"));
    room.playQueueItem(host.id, current.id, 1_000);
    const popular = room.addToQueue(guest.id, video("bbbbbbbbbbb", "Popular"));
    room.setVote(host.id, popular.id, true);
    const forced = room.addToQueue(host.id, video("ccccccccccc", "Forced"));
    const playbackRevision = room.playback.revision;

    assert.throws(
      () => room.forcePlayNext(guest.id, forced.id),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
    room.forcePlayNext(host.id, forced.id);
    const marked = room.publicStateFor(host.id);
    assert.equal(marked.playback.video?.title, "Current");
    assert.equal(marked.playback.revision, playbackRevision);
    assert.equal(marked.queue[0]?.id, popular.id);
    assert.equal(marked.queue.find((item) => item.id === forced.id)?.isForcedNext, true);

    room.control(host.id, "next", undefined, 2_000);
    assert.equal(room.playback.video?.title, "Forced");
    assert.equal(room.queue.has(popular.id), true);
    assert.equal(
      room.publicStateFor(host.id).queue.some((item) => item.isForcedNext),
      false,
    );
    assert.equal(room.history[0]?.id, current.id);

    const removed = room.addToQueue(host.id, video("ddddddddddd", "Removed"));
    room.forcePlayNext(host.id, removed.id);
    room.removeQueueItem(host.id, removed.id);
    room.control(host.id, "next", undefined, 3_000);
    assert.equal(room.playback.video?.title, "Popular");
  });

  it("lets only the host clear the queued items", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    room.addToQueue(guest.id, video("bbbbbbbbbbb", "Second"));

    assert.throws(
      () => room.clearQueue(guest.id),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
    room.clearQueue(host.id);
    assert.equal(room.publicStateFor(host.id).queue.length, 0);
  });

  it("imports a playlist atomically and can start idle host playback", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);

    const imported = room.addManyToQueue(
      host.id,
      [
        video("aaaaaaaaaaa", "First"),
        video("bbbbbbbbbbb", "Second"),
      ],
      true,
      1_000,
    );

    assert.equal(imported.length, 2);
    assert.equal(room.playback.video?.title, "First");
    assert.equal(room.publicStateFor(host.id).queue[0]?.video.title, "Second");
    assert.equal(room.publicStateFor(host.id).members[0]?.songsAddedCount, 2);
  });

  it("rejects an invalid playlist batch without adding any of it", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    room.addToQueue(host.id, video("aaaaaaaaaaa", "Existing"));
    const before = room.publicStateFor(host.id);

    assert.throws(
      () => room.addManyToQueue(host.id, [
        video("bbbbbbbbbbb", "Fresh"),
        video("aaaaaaaaaaa", "Duplicate"),
      ]),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );
    assert.deepEqual(room.publicStateFor(host.id).queue, before.queue);
    assert.equal(room.publicStateFor(host.id).members[0]?.songsAddedCount, 1);

    assert.throws(
      () => room.addManyToQueue(guest.id, [video("ccccccccccc", "Guest")], true),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
    assert.deepEqual(room.publicStateFor(host.id).queue, before.queue);
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
    assert.throws(
      () => room.addToQueue(host.id, {
        ...video("aaaaaaaaaaa", "Numeric ID"),
        videoId: 12_345_678_901 as unknown as string,
      }),
      (error) => error instanceof RoomError && error.statusCode === 400,
    );
    room.addToQueue(host.id, video("aaaaaaaaaaa", "First"));
    assert.throws(
      () => room.addToQueue(host.id, video("aaaaaaaaaaa", "Duplicate")),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );
  });

  it("preserves, bounds, and validates optional duration metadata", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const legacy = room.addToQueue(host.id, video("aaaaaaaaaaa", "Legacy"));
    const withDuration = room.addToQueue(host.id, {
      ...video("bbbbbbbbbbb", "Duration"),
      durationMs: 123_456.7,
    });
    const bounded = room.addToQueue(host.id, {
      ...video("ccccccccccc", "Bounded"),
      durationMs: Number.MAX_VALUE,
    });
    assert.throws(
      () => room.addToQueue(host.id, {
        ...video("ddddddddddd", "Invalid"),
        durationMs: Number.NaN,
      }),
      (error) => error instanceof RoomError && error.statusCode === 400,
    );

    assert.equal("durationMs" in legacy.video, false);
    assert.equal(withDuration.video.durationMs, 123_457);
    assert.equal(bounded.video.durationMs, 24 * 60 * 60 * 1_000);
    const snapshot = room.publicStateFor(host.id);
    assert.equal(snapshot.queue.find((item) => item.id === withDuration.id)?.video.durationMs, 123_457);

    room.playQueueItem(host.id, withDuration.id, 1_000);
    assert.equal(room.playback.video?.durationMs, 123_457);
  });

  it("exposes current attribution and completed history newest first", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    const first = room.addToQueue(guest.id, video("aaaaaaaaaaa", "First"));
    const second = room.addToQueue(host.id, video("bbbbbbbbbbb", "Second"));

    room.playQueueItem(host.id, first.id, 1_000);
    let state = room.publicStateFor(host.id, 1_100);
    assert.equal(state.playback.addedBy, guest.id);
    assert.equal(state.playback.addedByName, "Guest");
    assert.deepEqual(state.history, []);

    room.playQueueItem(host.id, second.id, 2_000);
    state = room.publicStateFor(host.id, 2_100);
    assert.equal(state.playback.addedBy, host.id);
    assert.equal(state.playback.addedByName, "Host");
    assert.deepEqual(
      state.history.map((item) => [item.id, item.addedBy, item.addedByName, item.playedAt]),
      [[first.id, guest.id, "Guest", 1_000]],
    );

    room.control(host.id, "next", undefined, 3_000);
    state = room.publicStateFor(host.id, 3_100);
    assert.equal(state.playback.video, null);
    assert.equal(state.playback.addedBy, undefined);
    assert.deepEqual(state.history.map((item) => item.id), [second.id, first.id]);
  });

  it("bounds playback history while preserving total songs-added counts", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const itemIds: string[] = [];
    for (let index = 0; index < 52; index += 1) {
      const item = room.addToQueue(
        host.id,
        video(index.toString(36).padStart(11, "0"), `Track ${index}`),
      );
      itemIds.push(item.id);
      room.playQueueItem(host.id, item.id, 1_000 + index);
    }

    const state = room.publicStateFor(host.id);
    assert.equal(state.history.length, 50);
    assert.equal(state.history[0]?.id, itemIds[50]);
    assert.equal(state.history[49]?.id, itemIds[1]);
    assert.equal(state.history.some((item) => item.id === itemIds[0]), false);
    assert.equal(
      state.members.find((member) => member.id === host.id)?.songsAddedCount,
      52,
    );
  });

  it("counts successful additions per member without decrementing removed songs", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    const first = room.addToQueue(guest.id, video("aaaaaaaaaaa", "First"));
    room.addToQueue(guest.id, video("bbbbbbbbbbb", "Second"));
    room.addToQueue(host.id, video("ccccccccccc", "Third"));
    assert.throws(() => room.addToQueue(guest.id, video("short", "Invalid")), RoomError);
    room.removeQueueItem(host.id, first.id);

    const state = room.publicStateFor(host.id);
    assert.equal(
      state.members.find((member) => member.id === guest.id)?.songsAddedCount,
      2,
    );
    assert.equal(
      state.members.find((member) => member.id === host.id)?.songsAddedCount,
      1,
    );
  });

  it("requires strictly more than forty percent yes votes to pause", () => {
    const cases: Array<[number, number]> = [[2, 1], [3, 2], [5, 3]];
    for (const [memberCount, expectedThreshold] of cases) {
      const room = new Room(`ROOM${memberCount}`);
      const members = Array.from({ length: memberCount }, (_, index) =>
        room.addMember(`Member ${index}`, index === 0));
      enablePauseVoting(room, members);
      const item = room.addToQueue(members[0]!.id, video("aaaaaaaaaaa", "Playing"));
      room.playQueueItem(members[0]!.id, item.id, 1_000);

      const requester = members[1]!;
      room.requestPause(requester.id, 2_000);
      let state = room.publicStateFor(requester.id, 2_000);
      assert.equal(state.pauseVote?.threshold, expectedThreshold);
      assert.equal(state.pauseVote?.yesVotes, 0);
      assert.equal(state.pauseVote?.requestedByName, "Member 1");
      assert.equal(state.pauseVote?.startedAt, 2_000);
      assert.equal(state.pauseVote?.expiresAt, 12_000);
      assert.equal(state.pauseVote?.eligibleVoters, memberCount);
      const pollId = currentPausePollId(room, requester.id, 2_000);
      room.castPauseVote(requester.id, "no", pollId, 2_100);
      state = room.publicStateFor(requester.id, 2_100);
      assert.equal(state.pauseVote?.noVotes, 1);
      assert.equal(state.pauseVote?.myVote, "no");

      for (let index = 0; index < expectedThreshold; index += 1) {
        const paused = room.castPauseVote(
          members[index]!.id,
          "yes",
          pollId,
          3_000 + index,
        );
        assert.equal(paused, index === expectedThreshold - 1);
      }
      state = room.publicStateFor(requester.id, 4_000);
      assert.equal(state.playback.status, "paused");
      assert.equal(state.pauseVote, undefined);
    }
  });

  it("counts only connected clients that support pause voting", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const requester = room.addMember("Requester");
    const legacy = room.addMember("Legacy listener");
    room.setConnected(host.id, true, 500);
    room.setConnected(requester.id, true, 500);
    room.setConnected(legacy.id, true, 500);
    room.setPauseVoteCapable(host.id, true, 500);
    room.setPauseVoteCapable(requester.id, true, 500);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(requester.id, 2_000);
    const state = room.publicStateFor(requester.id, 2_000);
    assert.equal(state.pauseVote?.eligibleVoters, 2);
    assert.equal(state.pauseVote?.threshold, 1);
    const pollId = currentPausePollId(room, requester.id, 2_000);

    assert.equal(room.castPauseVote(requester.id, "yes", pollId, 2_100), true);
    assert.equal(room.playback.status, "paused");
  });

  it("pauses immediately when only one online client can vote", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Legacy host", true);
    const requester = room.addMember("Requester");
    room.setConnected(host.id, true, 500);
    room.setConnected(requester.id, true, 500);
    room.setPauseVoteCapable(requester.id, true, 500);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(requester.id, 2_000);

    const state = room.publicStateFor(requester.id, 2_000);
    assert.equal(state.playback.status, "paused");
    assert.equal(state.playback.anchorServerTimeMs, 2_000);
    assert.equal(state.pauseVote, undefined);
  });

  it("rejects a delayed vote from an older pause poll", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const requester = room.addMember("Requester");
    const other = room.addMember("Other");
    enablePauseVoting(room, [host, requester, other]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(requester.id, 2_000);
    const oldPollId = currentPausePollId(room, requester.id, 2_000);
    room.castPauseVote(requester.id, "no", oldPollId, 2_100);
    room.expirePauseVote(12_000);
    room.requestPause(requester.id, 12_001);
    const newPollId = currentPausePollId(room, requester.id, 12_001);
    assert.notEqual(newPollId, oldPollId);

    assert.throws(
      () => room.castPauseVote(other.id, "yes", oldPollId, 12_100),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );
    assert.equal(room.publicStateFor(host.id, 12_100).pauseVote?.yesVotes, 0);
    assert.equal(room.playback.status, "playing");
  });

  it("resolves an expired zero-response poll before accepting another request", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const requester = room.addMember("Requester");
    enablePauseVoting(room, [host, requester]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(requester.id, 2_000);
    assert.throws(
      () => room.requestPause(requester.id, 12_000),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );
    assert.equal(room.playback.status, "paused");
    assert.equal(room.publicStateFor(host.id, 12_000).pauseVote, undefined);
  });

  it("auto-pauses when a ten-second pause poll expires without votes", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    enablePauseVoting(room, [host, guest]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(guest.id, 2_000);
    assert.equal(room.expirePauseVote(11_999), false);
    assert.equal(room.playback.status, "playing");
    assert.equal(room.expirePauseVote(12_000), true);
    assert.equal(room.playback.status, "paused");
    assert.equal(room.publicStateFor(host.id, 12_000).pauseVote, undefined);
    assert.equal(room.expirePauseVote(12_000), false);
  });

  it("closes an expired pause poll with any votes and keeps playing", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const requester = room.addMember("Requester");
    const other = room.addMember("Other");
    enablePauseVoting(room, [host, requester, other]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(requester.id, 2_000);
    const pollId = currentPausePollId(room, requester.id, 2_000);
    assert.equal(room.castPauseVote(requester.id, "yes", pollId, 2_500), false);
    assert.equal(room.publicStateFor(host.id, 11_999).pauseVote?.yesVotes, 1);
    const revision = room.playback.revision;

    assert.equal(room.expirePauseVote(12_000), true);
    assert.equal(room.playback.status, "playing");
    assert.equal(room.playback.revision, revision);
    assert.equal(room.publicStateFor(host.id, 12_000).pauseVote, undefined);
  });

  it("closes a losing pause poll as soon as every connected member votes", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const requester = room.addMember("Requester");
    const other = room.addMember("Other");
    enablePauseVoting(room, [host, requester, other]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(requester.id, 2_000);
    const pollId = currentPausePollId(room, requester.id, 2_000);
    assert.equal(room.castPauseVote(requester.id, "yes", pollId, 2_100), false);
    assert.equal(room.castPauseVote(other.id, "no", pollId, 2_200), false);
    assert.equal(room.publicStateFor(host.id, 2_200).pauseVote?.yesVotes, 1);

    assert.equal(room.castPauseVote(host.id, "no", pollId, 2_300), false);
    assert.equal(room.playback.status, "playing");
    assert.equal(room.publicStateFor(host.id, 2_300).pauseVote, undefined);
  });

  it("clears pause polls on playback or requester changes", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    enablePauseVoting(room, [host, guest]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);

    room.requestPause(guest.id, 33_000);
    room.control(host.id, "seek", 5_000, 34_000);
    assert.equal(room.publicStateFor(host.id, 34_000).pauseVote, undefined);

    room.requestPause(guest.id, 35_000);
    room.control(host.id, "pause", undefined, 36_000);
    assert.equal(room.playback.status, "paused");
    assert.equal(room.publicStateFor(host.id, 36_000).pauseVote, undefined);
    assert.throws(
      () => room.requestPause(guest.id, 37_000),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );

    room.control(host.id, "play", undefined, 38_000);
    room.requestPause(guest.id, 39_000);
    room.setConnected(guest.id, false, 40_000);
    assert.equal(room.publicStateFor(host.id, 40_000).pauseVote, undefined);

    room.setConnected(guest.id, true, 41_000);
    room.setPauseVoteCapable(guest.id, true, 41_000);
    room.requestPause(guest.id, 42_000);
    room.setConnected(host.id, false, 43_000);
    assert.equal(room.publicStateFor(guest.id, 43_000).me.isHost, true);
    assert.equal(room.publicStateFor(guest.id, 43_000).pauseVote, undefined);
  });

  it("supports bounded chat with host deletion and member muting", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    room.setConnected(host.id, true, 100);
    room.setConnected(guest.id, true, 100);

    const first = room.sendChat(guest.id, "  Hello room  ", 1_000);
    assert.equal(first.text, "Hello room");
    assert.equal(room.publicStateFor(host.id, 1_000).chat[0]?.displayName, "Guest");
    assert.throws(
      () => room.sendChat(guest.id, "Too fast", 1_100),
      (error) => error instanceof RoomError && error.statusCode === 429,
    );

    assert.throws(
      () => room.setChatMuted(guest.id, host.id, true, 2_000),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
    room.setChatMuted(host.id, guest.id, true, 2_000);
    assert.equal(
      room.publicStateFor(host.id, 2_000).members.find((member) => member.id === guest.id)
        ?.chatMuted,
      true,
    );
    assert.throws(
      () => room.sendChat(guest.id, "Muted", 3_000),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
    room.setChatMuted(host.id, guest.id, false, 3_000);

    assert.throws(
      () => room.deleteChatMessage(guest.id, first.id, 4_000),
      (error) => error instanceof RoomError && error.statusCode === 403,
    );
    room.deleteChatMessage(host.id, first.id, 4_000);
    assert.equal(room.publicStateFor(host.id, 4_000).chat.length, 0);

    for (let index = 0; index < 101; index += 1) {
      room.sendChat(host.id, `Message ${index}`, 5_000 + index * 1_000);
    }
    const boundedChat = room.publicStateFor(host.id, 200_000).chat;
    assert.equal(boundedChat.length, 100);
    assert.equal(boundedChat[0]?.text, "Message 1");
    assert.equal(boundedChat.at(-1)?.text, "Message 100");
  });

  it("round-trips durable room state while resetting process-local presence", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const guest = room.addMember("Guest");
    room.setConnected(host.id, true, 100);
    room.setConnected(guest.id, true, 100);
    const current = room.addToQueue(host.id, video("aaaaaaaaaaa", "Current"));
    room.playQueueItem(host.id, current.id, 1_000);
    const queued = room.addToQueue(guest.id, video("bbbbbbbbbbb", "Queued"));
    room.forcePlayNext(host.id, queued.id);
    room.sendChat(guest.id, "Persist me", 2_000);
    room.setChatMuted(host.id, guest.id, true, 3_000);

    const serialized = JSON.parse(JSON.stringify(room.toStoredState())) as unknown;
    const restored = Room.fromStoredState(serialized);

    assert.equal(restored.authenticate(host.id, host.token).displayName, "Host");
    const state = restored.publicStateFor(host.id, 4_000);
    assert.equal(state.members.every((member) => member.connected === false), true);
    assert.equal(state.playback.video?.title, "Current");
    assert.equal(state.queue[0]?.video.title, "Queued");
    assert.equal(state.queue[0]?.isForcedNext, true);
    assert.equal(state.chat[0]?.text, "Persist me");
    assert.equal(
      state.members.find((member) => member.id === guest.id)?.chatMuted,
      true,
    );

    restored.setConnected(host.id, true, 4_100);
    restored.control(host.id, "next", undefined, 5_000);
    assert.equal(restored.playback.video?.title, "Queued");
    assert.equal(restored.history[0]?.video.title, "Current");
  });

  it("canonicalizes restored objects so unknown persisted fields cannot escape", () => {
    const source = new Room("ABC123");
    const host = source.addMember("Host", true);
    source.setConnected(host.id, true, 500);
    const current = source.addToQueue(host.id, video("aaaaaaaaaaa", "Current"));
    source.playQueueItem(host.id, current.id, 1_000);
    source.addToQueue(host.id, video("bbbbbbbbbbb", "Queued"));
    source.sendChat(host.id, "Persisted", 2_000);
    const stored = JSON.parse(JSON.stringify(source.toStoredState())) as Record<string, unknown>;
    const marker = "must-not-survive";
    (stored.members as Array<Record<string, unknown>>)[0]!.unbounded = marker;
    (stored.queue as Array<Record<string, unknown>>)[0]!.unbounded = marker;
    ((stored.queue as Array<Record<string, unknown>>)[0]!.video as Record<string, unknown>)
      .unbounded = marker;
    (stored.chat as Array<Record<string, unknown>>)[0]!.unbounded = marker;
    (stored.playback as Record<string, unknown>).unbounded = marker;

    const restored = Room.fromStoredState(stored);
    assert.equal(JSON.stringify(restored.toStoredState()).includes(marker), false);
    assert.equal(JSON.stringify(restored.publicStateFor(host.id)).includes(marker), false);
  });

  it("removes a disconnected member's skip vote", () => {
    const room = new Room("ABC123");
    const host = room.addMember("Host", true);
    const first = room.addMember("First");
    const second = room.addMember("Second");
    room.setConnected(host.id, true);
    room.setConnected(first.id, true);
    room.setConnected(second.id, true);
    const current = room.addToQueue(host.id, video("aaaaaaaaaaa", "Current"));
    room.playQueueItem(host.id, current.id, 500);
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
    const current = room.addToQueue(host.id, video("aaaaaaaaaaa", "Current"));
    room.playQueueItem(host.id, current.id, 500);
    const item = room.addToQueue(host.id, video("bbbbbbbbbbb", "First"));
    room.setVote(guest.id, item.id, true);
    room.voteToSkip(host.id);

    room.removeMember(host.id);

    assert.equal(room.hasMember(host.id), false);
    assert.equal(room.publicStateFor(guest.id).me.isHost, true);
    const remainingItem = room.publicStateFor(guest.id).queue[0];
    assert.equal(remainingItem?.voteCount, 1);
    assert.equal(remainingItem?.addedBy, host.id);
    assert.equal(remainingItem?.addedByName, "Host");
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

  it("resolves expired pause votes so the server can broadcast the change", () => {
    const rooms = new RoomManager();
    const { room, member: host } = rooms.createRoom("Host");
    const guest = room.addMember("Guest");
    enablePauseVoting(room, [host, guest]);
    const item = room.addToQueue(host.id, video("aaaaaaaaaaa", "Playing"));
    room.playQueueItem(host.id, item.id, 1_000);
    room.requestPause(guest.id, 2_000);
    const pollId = currentPausePollId(room, guest.id, 2_000);

    assert.equal(room.publicStateFor(host.id, 12_000).pauseVote, undefined);
    assert.throws(
      () => room.castPauseVote(guest.id, "yes", pollId, 12_000),
      (error) => error instanceof RoomError && error.statusCode === 409,
    );
    assert.equal(room.playback.status, "playing");
    assert.deepEqual(rooms.expirePauseVotes(12_000), [room.code]);
    assert.equal(room.playback.status, "paused");
    assert.deepEqual(rooms.expirePauseVotes(12_000), []);
  });

  it("emits persistence hooks for room changes and pruning", () => {
    const changed: string[] = [];
    const deleted: string[] = [];
    const rooms = new RoomManager({
      onRoomChanged: (room) => changed.push(room.code),
      onRoomDeleted: (code) => deleted.push(code),
    });
    const created = rooms.createRoom("Host");
    assert.ok(changed.includes(created.room.code));
    created.room.lastActivityAt = 1_000;

    assert.deepEqual(rooms.pruneInactiveRooms(5_000, 10_000), [created.room.code]);
    assert.deepEqual(deleted, [created.room.code]);
  });

  it("rejects malformed persisted rooms", () => {
    const rooms = new RoomManager();
    assert.throws(
      () => rooms.restoreRoom({ version: 1, code: "BAD" }),
      (error) => error instanceof RoomError && error.message === "Invalid stored room state",
    );
  });

  it("rejects malformed nested persisted room data", () => {
    const source = new Room("ABC123");
    const host = source.addMember("Host", true);
    source.addToQueue(host.id, video("aaaaaaaaaaa", "Queued"));
    const stored = JSON.parse(JSON.stringify(source.toStoredState())) as Record<string, unknown>;
    const members = stored.members as unknown[];
    const queue = stored.queue as Array<Record<string, unknown>>;
    const secondQueueItem = {
      ...queue[0],
      id: "second-queue-item",
      video: video("bbbbbbbbbbb", "Second"),
      orderKey: 1,
    };
    const malformedStates = [
      { ...stored, playback: [] },
      { ...stored, history: ["not a history item"] },
      { ...stored, chat: [42] },
      { ...stored, currentPlaybackItem: {} },
      { ...stored, members: [...members, members[0]] },
      {
        ...stored,
        members: [
          ...members.map((member) => ({
            ...(member as Record<string, unknown>),
            isHost: true,
          })),
          {
            ...(members[0] as Record<string, unknown>),
            id: "second-host",
            token: "second-token",
            isHost: true,
          },
        ],
      },
      {
        ...stored,
        playback: {
          ...(stored.playback as Record<string, unknown>),
          status: "playing",
          video: null,
        },
      },
      {
        ...stored,
        playback: {
          ...(stored.playback as Record<string, unknown>),
          status: "idle",
          video: (queue[0]?.video as unknown),
        },
      },
      {
        ...stored,
        queue: [{ ...queue[0], votes: [host.id, "unknown-member"] }],
      },
      {
        ...stored,
        queue: [queue[0], { ...secondQueueItem, video: queue[0]?.video }],
        nextOrderKey: 2,
      },
      {
        ...stored,
        queue: [queue[0], { ...secondQueueItem, orderKey: queue[0]?.orderKey }],
        nextOrderKey: 2,
      },
      {
        ...stored,
        history: [{ ...queue[0], playedAt: 1_000 }],
      },
      { ...stored, nextOrderKey: 0 },
      {
        ...stored,
        playback: {
          ...(stored.playback as Record<string, unknown>),
          revision: Number.MAX_SAFE_INTEGER,
        },
      },
      { ...stored, mutedMemberIds: ["unknown-member"] },
    ];

    for (const malformed of malformedStates) {
      assert.throws(
        () => Room.fromStoredState(malformed),
        (error) => error instanceof RoomError && error.message === "Invalid stored room state",
      );
    }

    const playingSource = new Room("DEF456");
    const playingHost = playingSource.addMember("Host", true);
    const playingItem = playingSource.addToQueue(
      playingHost.id,
      video("ccccccccccc", "Playing"),
    );
    playingSource.playQueueItem(playingHost.id, playingItem.id, 2_000);
    const playingStored = JSON.parse(
      JSON.stringify(playingSource.toStoredState()),
    ) as Record<string, unknown>;
    const playback = playingStored.playback as Record<string, unknown>;
    const playbackVideo = playback.video as Record<string, unknown>;
    assert.throws(
      () => Room.fromStoredState({
        ...playingStored,
        playback: {
          ...playback,
          video: { ...playbackVideo, title: "Mismatched metadata" },
        },
      }),
      (error) => error instanceof RoomError && error.message === "Invalid stored room state",
    );
  });
});
