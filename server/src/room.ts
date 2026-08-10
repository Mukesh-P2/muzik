import { randomBytes, randomUUID } from "node:crypto";
import type {
  Member,
  PlaybackState,
  PublicQueueItem,
  QueueItem,
  VideoSummary,
} from "./types.js";

const PLAY_LEAD_MS = 1_200;
const CONTROL_LEAD_MS = 500;
const MAX_QUEUE_ITEMS = 100;
const MAX_POSITION_MS = 24 * 60 * 60 * 1_000;

export class RoomError extends Error {
  constructor(
    message: string,
    readonly statusCode = 400,
  ) {
    super(message);
  }
}

export class Room {
  readonly members = new Map<string, Member>();
  readonly queue = new Map<string, QueueItem>();
  readonly skipVotes = new Set<string>();
  readonly createdAt = Date.now();
  lastActivityAt = this.createdAt;

  playback: PlaybackState = {
    video: null,
    status: "idle",
    positionMs: 0,
    anchorServerTimeMs: Date.now(),
    revision: 0,
  };

  constructor(readonly code: string) {}

  addMember(displayName: string, isHost = false): Member {
    const normalizedName = displayName.trim().slice(0, 40);
    if (!normalizedName) throw new RoomError("Display name is required");
    if (this.members.size >= 50) throw new RoomError("Room is full", 409);

    const member: Member = {
      id: randomUUID(),
      token: randomBytes(24).toString("base64url"),
      displayName: normalizedName,
      isHost,
      connected: false,
      joinedAt: Date.now(),
    };
    this.members.set(member.id, member);
    this.touch();
    return member;
  }

  authenticate(memberId: string, token: string): Member {
    const member = this.members.get(memberId);
    if (!member || member.token !== token) {
      throw new RoomError("Invalid room membership", 401);
    }
    return member;
  }

  hasMember(memberId: string): boolean {
    return this.members.has(memberId);
  }

  removeMember(memberId: string): void {
    const member = this.requireMember(memberId);
    this.members.delete(memberId);
    this.skipVotes.delete(memberId);
    for (const item of this.queue.values()) item.votes.delete(memberId);
    if (member.isHost) this.ensureConnectedHost();
    this.touch();
  }

  setConnected(memberId: string, connected: boolean): void {
    const member = this.requireMember(memberId);
    member.connected = connected;
    if (!connected) this.skipVotes.delete(memberId);
    if (!connected) member.isHost = false;
    this.ensureConnectedHost();
    this.touch();
  }

  addToQueue(memberId: string, video: VideoSummary): QueueItem {
    this.requireMember(memberId);
    if (this.queue.size >= MAX_QUEUE_ITEMS) {
      throw new RoomError("Queue is full", 409);
    }
    const cleanVideo = validateVideo(video);
    const duplicate = [...this.queue.values()].some(
      (item) => item.video.videoId === cleanVideo.videoId,
    );
    if (duplicate || this.playback.video?.videoId === cleanVideo.videoId) {
      throw new RoomError("Video is already in this room", 409);
    }

    const item: QueueItem = {
      id: randomUUID(),
      video: cleanVideo,
      addedBy: memberId,
      addedAt: Date.now(),
      votes: new Set([memberId]),
    };
    this.queue.set(item.id, item);
    this.touch();
    return item;
  }

  setVote(memberId: string, itemId: string, enabled: boolean): void {
    this.requireMember(memberId);
    const item = this.queue.get(itemId);
    if (!item) throw new RoomError("Queue item not found", 404);
    if (enabled) item.votes.add(memberId);
    else item.votes.delete(memberId);
    this.touch();
  }

  removeQueueItem(memberId: string, itemId: string): void {
    this.requireHost(memberId);
    if (!this.queue.delete(itemId)) {
      throw new RoomError("Queue item not found", 404);
    }
    this.touch();
  }

  playQueueItem(memberId: string, itemId: string, now = Date.now()): void {
    this.requireHost(memberId);
    const item = this.queue.get(itemId);
    if (!item) throw new RoomError("Queue item not found", 404);
    this.queue.delete(itemId);
    this.startVideo(item.video, now);
    this.touch(now);
  }

  control(
    memberId: string,
    action: "play" | "pause" | "seek" | "next",
    positionMs: number | undefined,
    now = Date.now(),
  ): void {
    this.requireHost(memberId);

    if (action === "next") {
      this.playNext(now);
      this.touch(now);
      return;
    }

    if (!this.playback.video) {
      if (action === "play") this.playNext(now);
      else throw new RoomError("Nothing is playing", 409);
      this.touch(now);
      return;
    }

    if (action === "play") {
      if (this.playback.status === "playing") return;
      this.playback = {
        ...this.playback,
        status: "playing",
        anchorServerTimeMs: now + PLAY_LEAD_MS,
        revision: this.playback.revision + 1,
      };
      this.touch(now);
      return;
    }

    if (action === "pause") {
      if (this.playback.status === "paused") return;
      const effectiveAt = now + CONTROL_LEAD_MS;
      this.playback = {
        ...this.playback,
        status: "paused",
        positionMs: this.positionAt(effectiveAt),
        anchorServerTimeMs: effectiveAt,
        revision: this.playback.revision + 1,
      };
      this.touch(now);
      return;
    }

    if (!Number.isFinite(positionMs) || positionMs! < 0 || positionMs! > MAX_POSITION_MS) {
      throw new RoomError("Seek position must be between 0 and 24 hours");
    }
    this.playback = {
      ...this.playback,
      positionMs: Math.floor(positionMs!),
      anchorServerTimeMs: now + CONTROL_LEAD_MS,
      revision: this.playback.revision + 1,
    };
    this.touch(now);
  }

  voteToSkip(memberId: string, now = Date.now()): boolean {
    this.requireMember(memberId);
    this.skipVotes.add(memberId);
    this.touch(now);
    const connectedCount = [...this.members.values()].filter(
      (member) => member.connected,
    ).length;
    const threshold = Math.max(1, Math.ceil(connectedCount / 2));
    if (this.skipVotes.size >= threshold) {
      this.playNext(now);
      return true;
    }
    return false;
  }

  positionAt(serverTimeMs: number): number {
    if (this.playback.status !== "playing") return this.playback.positionMs;
    return Math.max(
      0,
      this.playback.positionMs +
        Math.max(0, serverTimeMs - this.playback.anchorServerTimeMs),
    );
  }

  hasConnectedMembers(): boolean {
    return [...this.members.values()].some((member) => member.connected);
  }

  publicStateFor(memberId: string, now = Date.now()) {
    const member = this.requireMember(memberId);
    const members = [...this.members.values()].map((candidate) => ({
      id: candidate.id,
      displayName: candidate.displayName,
      isHost: candidate.isHost,
      connected: candidate.connected,
    }));
    const queue: PublicQueueItem[] = this.sortedQueue().map((item) => ({
      id: item.id,
      video: item.video,
      addedBy: item.addedBy,
      addedAt: item.addedAt,
      voteCount: item.votes.size,
      votedByMe: item.votes.has(memberId),
    }));
    const connectedCount = members.filter((candidate) => candidate.connected).length;

    return {
      code: this.code,
      serverTimeMs: now,
      me: {
        id: member.id,
        displayName: member.displayName,
        isHost: member.isHost,
      },
      members,
      queue,
      playback: this.playback,
      skip: {
        votes: this.skipVotes.size,
        threshold: Math.max(1, Math.ceil(connectedCount / 2)),
        votedByMe: this.skipVotes.has(memberId),
      },
    };
  }

  private startVideo(video: VideoSummary, now: number): void {
    this.skipVotes.clear();
    this.playback = {
      video,
      status: "playing",
      positionMs: 0,
      anchorServerTimeMs: now + PLAY_LEAD_MS,
      revision: this.playback.revision + 1,
    };
  }

  private playNext(now: number): void {
    const item = this.sortedQueue()[0];
    if (!item) {
      this.skipVotes.clear();
      this.playback = {
        video: null,
        status: "idle",
        positionMs: 0,
        anchorServerTimeMs: now,
        revision: this.playback.revision + 1,
      };
      return;
    }
    this.queue.delete(item.id);
    this.startVideo(item.video, now);
  }

  private sortedQueue(): QueueItem[] {
    return [...this.queue.values()].sort(
      (a, b) => b.votes.size - a.votes.size || a.addedAt - b.addedAt,
    );
  }

  private ensureConnectedHost(): void {
    if ([...this.members.values()].some((member) => member.connected && member.isHost)) {
      return;
    }
    for (const member of this.members.values()) member.isHost = false;
    const replacement = [...this.members.values()]
      .filter((member) => member.connected)
      .sort((a, b) => a.joinedAt - b.joinedAt)[0];
    if (replacement) replacement.isHost = true;
  }

  private touch(now = Date.now()): void {
    this.lastActivityAt = Math.max(this.lastActivityAt, now);
  }

  private requireMember(memberId: string): Member {
    const member = this.members.get(memberId);
    if (!member) throw new RoomError("Member not found", 404);
    return member;
  }

  private requireHost(memberId: string): Member {
    const member = this.requireMember(memberId);
    if (!member.isHost) throw new RoomError("Host permission required", 403);
    return member;
  }
}

export class RoomManager {
  private readonly rooms = new Map<string, Room>();

  createRoom(displayName: string): { room: Room; member: Member } {
    let code: string;
    do code = randomBytes(3).toString("hex").toUpperCase();
    while (this.rooms.has(code));

    const room = new Room(code);
    const member = room.addMember(displayName, true);
    this.rooms.set(code, room);
    return { room, member };
  }

  getRoom(code: string): Room {
    const room = this.rooms.get(code.trim().toUpperCase());
    if (!room) throw new RoomError("Room not found", 404);
    return room;
  }

  pruneInactiveRooms(maxIdleMs: number, now = Date.now()): string[] {
    const removed: string[] = [];
    for (const [code, room] of this.rooms) {
      if (!room.hasConnectedMembers() && now - room.lastActivityAt >= maxIdleMs) {
        this.rooms.delete(code);
        removed.push(code);
      }
    }
    return removed;
  }
}

function validateVideo(video: VideoSummary): VideoSummary {
  if (!video || !/^[A-Za-z0-9_-]{11}$/.test(video.videoId ?? "")) {
    throw new RoomError("A valid YouTube video ID is required");
  }
  const title = String(video.title ?? "").trim().slice(0, 200);
  if (!title) throw new RoomError("Video title is required");
  return {
    videoId: video.videoId,
    title,
    channelTitle: String(video.channelTitle ?? "").trim().slice(0, 100),
    thumbnailUrl: String(video.thumbnailUrl ?? "").trim().slice(0, 500),
  };
}
