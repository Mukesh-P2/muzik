import { randomBytes, randomUUID } from "node:crypto";
import type {
  ChatMessage,
  HistoryItem,
  Member,
  PauseVoteChoice,
  PlaybackState,
  PublicPauseVoteState,
  PublicQueueItem,
  QueueItem,
  VideoSummary,
} from "./types.js";

const PLAY_LEAD_MS = 1_200;
const CONTROL_LEAD_MS = 500;
const MAX_QUEUE_ITEMS = 100;
const MAX_QUEUE_IMPORT_ITEMS = 50;
const MAX_HISTORY_ITEMS = 50;
const MAX_CHAT_MESSAGES = 100;
const MAX_CHAT_MESSAGE_LENGTH = 500;
const CHAT_COOLDOWN_MS = 750;
const MAX_POSITION_MS = 24 * 60 * 60 * 1_000;
const MAX_INCREMENTING_COUNTER = 1_000_000_000_000;
const PAUSE_VOTE_TTL_MS = 10_000;

interface PauseVote {
  id: string;
  requestedBy: string;
  requestedByName: string;
  votes: Map<string, PauseVoteChoice>;
  startedAt: number;
  expiresAt: number;
}

export interface StoredRoomStateV1 {
  version: 1;
  code: string;
  createdAt: number;
  lastActivityAt: number;
  members: Member[];
  queue: Array<Omit<QueueItem, "votes"> & { votes: string[] }>;
  history: HistoryItem[];
  chat: ChatMessage[];
  playback: PlaybackState;
  currentPlaybackItem?: HistoryItem;
  forcedNextItemId?: string;
  mutedMemberIds: string[];
  nextOrderKey: number;
}

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
  readonly history: HistoryItem[] = [];
  readonly chat: ChatMessage[] = [];
  readonly skipVotes = new Set<string>();
  readonly createdAt: number;
  lastActivityAt: number;
  private currentPlaybackItem: HistoryItem | undefined;
  private forcedNextItemId: string | undefined;
  private pauseVote: PauseVote | undefined;
  private readonly mutedMemberIds = new Set<string>();
  private readonly lastChatAt = new Map<string, number>();
  private nextOrderKey = 0;

  playback: PlaybackState = {
    video: null,
    status: "idle",
    positionMs: 0,
    anchorServerTimeMs: Date.now(),
    revision: 0,
  };

  constructor(
    readonly code: string,
    private readonly onChange: (room: Room) => void = () => {},
    createdAt = Date.now(),
  ) {
    this.createdAt = createdAt;
    this.lastActivityAt = createdAt;
    this.playback.anchorServerTimeMs = createdAt;
  }

  static fromStoredState(
    value: unknown,
    onChange: (room: Room) => void = () => {},
  ): Room {
    if (!isStoredRoomState(value)) throw new RoomError("Invalid stored room state");
    const room = new Room(value.code, onChange, value.createdAt);
    room.lastActivityAt = value.lastActivityAt;
    for (const member of value.members) {
      room.members.set(member.id, {
        id: member.id,
        token: member.token,
        displayName: member.displayName,
        isHost: member.isHost,
        connected: false,
        joinedAt: member.joinedAt,
        songsAddedCount: member.songsAddedCount,
        pauseVoteCapable: false,
      });
    }
    for (const item of value.queue) {
      room.queue.set(item.id, {
        id: item.id,
        video: validateVideo(item.video),
        addedBy: item.addedBy,
        ...(item.addedByName === undefined ? {} : { addedByName: item.addedByName }),
        addedAt: item.addedAt,
        votes: new Set(item.votes.filter((memberId) => room.members.has(memberId))),
        orderKey: item.orderKey,
      });
    }
    room.history.push(...value.history.map(canonicalHistoryItem));
    room.chat.push(...value.chat.slice(-MAX_CHAT_MESSAGES).map(canonicalChatMessage));
    room.playback = canonicalPlaybackState(value.playback);
    room.currentPlaybackItem = value.currentPlaybackItem
      ? canonicalHistoryItem(value.currentPlaybackItem)
      : undefined;
    room.forcedNextItemId = value.forcedNextItemId && room.queue.has(value.forcedNextItemId)
      ? value.forcedNextItemId
      : undefined;
    for (const memberId of value.mutedMemberIds) {
      if (room.members.has(memberId)) room.mutedMemberIds.add(memberId);
    }
    room.nextOrderKey = Math.max(
      value.nextOrderKey,
      ...[...room.queue.values()].map((item) => item.orderKey + 1),
      0,
    );
    return room;
  }

  toStoredState(): StoredRoomStateV1 {
    return {
      version: 1,
      code: this.code,
      createdAt: this.createdAt,
      lastActivityAt: this.lastActivityAt,
      members: [...this.members.values()],
      queue: [...this.queue.values()].map((item) => ({
        ...item,
        votes: [...item.votes],
      })),
      history: this.history,
      chat: this.chat,
      playback: this.playback,
      ...(this.currentPlaybackItem
        ? { currentPlaybackItem: this.currentPlaybackItem }
        : {}),
      ...(this.forcedNextItemId ? { forcedNextItemId: this.forcedNextItemId } : {}),
      mutedMemberIds: [...this.mutedMemberIds],
      nextOrderKey: this.nextOrderKey,
    };
  }

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
      songsAddedCount: 0,
      pauseVoteCapable: false,
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

  removeMember(memberId: string, now = Date.now()): void {
    const member = this.requireMember(memberId);
    this.members.delete(memberId);
    this.skipVotes.delete(memberId);
    this.mutedMemberIds.delete(memberId);
    this.lastChatAt.delete(memberId);
    for (const item of this.queue.values()) item.votes.delete(memberId);
    if (this.pauseVote?.requestedBy === memberId) this.pauseVote = undefined;
    else this.pauseVote?.votes.delete(memberId);
    if (member.isHost) this.ensureConnectedHost();
    this.reconcilePauseVote(now);
    this.touch(now);
  }

  setConnected(memberId: string, connected: boolean, now = Date.now()): void {
    const member = this.requireMember(memberId);
    member.connected = connected;
    if (!connected) this.skipVotes.delete(memberId);
    if (!connected) member.pauseVoteCapable = false;
    if (!connected) member.isHost = false;
    this.ensureConnectedHost();
    this.reconcilePauseVote(now);
    this.touch(now);
  }

  setPauseVoteCapable(memberId: string, capable: boolean, now = Date.now()): void {
    const member = this.requireMember(memberId);
    const nextValue = member.connected && capable;
    if (member.pauseVoteCapable === nextValue) return;
    member.pauseVoteCapable = nextValue;
    this.reconcilePauseVote(now);
    this.touch(now);
  }

  addToQueue(memberId: string, video: VideoSummary): QueueItem {
    const member = this.requireMember(memberId);
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
      addedByName: member.displayName,
      addedAt: Date.now(),
      votes: new Set([memberId]),
      orderKey: this.nextOrderKey++,
    };
    this.queue.set(item.id, item);
    member.songsAddedCount += 1;
    this.touch();
    return item;
  }

  addManyToQueue(
    memberId: string,
    videos: readonly VideoSummary[],
    startPlayback = false,
    now = Date.now(),
  ): QueueItem[] {
    const member = this.requireMember(memberId);
    if (!Array.isArray(videos) || videos.length < 1 || videos.length > MAX_QUEUE_IMPORT_ITEMS) {
      throw new RoomError(`Playlist imports must contain 1 to ${MAX_QUEUE_IMPORT_ITEMS} videos`);
    }
    if (startPlayback && !member.isHost) {
      throw new RoomError("Host permission required", 403);
    }
    if (startPlayback && (this.playback.video !== null || this.playback.status !== "idle")) {
      throw new RoomError("Playback must be idle to start an imported playlist", 409);
    }
    if (this.queue.size + videos.length > MAX_QUEUE_ITEMS) {
      throw new RoomError("The playlist does not fit in the room queue", 409);
    }

    const cleanVideos = videos.map(validateVideo);
    const seenVideoIds = new Set(
      [...this.queue.values()].map((item) => item.video.videoId),
    );
    if (this.playback.video) seenVideoIds.add(this.playback.video.videoId);
    for (const video of cleanVideos) {
      if (seenVideoIds.has(video.videoId)) {
        throw new RoomError("Playlist contains a video that is already in this room", 409);
      }
      seenVideoIds.add(video.videoId);
    }

    const items = cleanVideos.map<QueueItem>((video) => ({
      id: randomUUID(),
      video,
      addedBy: memberId,
      addedByName: member.displayName,
      addedAt: now,
      votes: new Set([memberId]),
      orderKey: this.nextOrderKey++,
    }));
    for (const item of items) this.queue.set(item.id, item);
    member.songsAddedCount += items.length;
    if (startPlayback) this.playNext(now);
    this.touch(now);
    return items;
  }

  setVote(memberId: string, itemId: string, enabled: boolean): void {
    this.requireMember(memberId);
    const item = this.queue.get(itemId);
    if (!item) throw new RoomError("Queue item not found", 404);
    if (enabled) item.votes.add(memberId);
    else item.votes.delete(memberId);
    this.touch();
  }

  sendChat(memberId: string, text: string, now = Date.now()): ChatMessage {
    const member = this.requireMember(memberId);
    if (!member.connected) throw new RoomError("Only connected members can chat", 409);
    if (this.mutedMemberIds.has(memberId)) {
      throw new RoomError("The host muted your room chat", 403);
    }
    const normalizedText = String(text ?? "").trim().slice(0, MAX_CHAT_MESSAGE_LENGTH);
    if (!normalizedText) throw new RoomError("Chat message is required");
    const lastSentAt = this.lastChatAt.get(memberId);
    if (lastSentAt !== undefined && now - lastSentAt < CHAT_COOLDOWN_MS) {
      throw new RoomError("Please wait before sending another message", 429);
    }

    const message: ChatMessage = {
      id: randomUUID(),
      memberId,
      displayName: member.displayName,
      text: normalizedText,
      sentAt: now,
    };
    this.chat.push(message);
    if (this.chat.length > MAX_CHAT_MESSAGES) this.chat.shift();
    this.lastChatAt.set(memberId, now);
    this.touch(now);
    return message;
  }

  deleteChatMessage(memberId: string, messageId: string, now = Date.now()): void {
    this.requireHost(memberId);
    const index = this.chat.findIndex((message) => message.id === messageId);
    if (index < 0) throw new RoomError("Chat message not found", 404);
    this.chat.splice(index, 1);
    this.touch(now);
  }

  setChatMuted(
    memberId: string,
    targetMemberId: string,
    muted: boolean,
    now = Date.now(),
  ): void {
    const host = this.requireHost(memberId);
    const target = this.requireMember(targetMemberId);
    if (target.id === host.id) throw new RoomError("The host cannot mute themselves", 409);
    if (muted) this.mutedMemberIds.add(target.id);
    else this.mutedMemberIds.delete(target.id);
    this.touch(now);
  }

  removeQueueItem(memberId: string, itemId: string): void {
    this.requireHost(memberId);
    if (!this.queue.delete(itemId)) {
      throw new RoomError("Queue item not found", 404);
    }
    if (this.forcedNextItemId === itemId) this.forcedNextItemId = undefined;
    this.touch();
  }

  clearQueue(memberId: string): void {
    this.requireHost(memberId);
    this.queue.clear();
    this.forcedNextItemId = undefined;
    this.touch();
  }

  reorderQueueItem(
    memberId: string,
    itemId: string,
    beforeItemId?: string | null,
  ): void {
    this.requireHost(memberId);
    const item = this.queue.get(itemId);
    if (!item) throw new RoomError("Queue item not found", 404);

    let beforeItem: QueueItem | undefined;
    if (beforeItemId !== undefined && beforeItemId !== null) {
      beforeItem = this.queue.get(beforeItemId);
      if (!beforeItem) throw new RoomError("Queue item not found", 404);
      if (beforeItem.votes.size !== item.votes.size) {
        throw new RoomError("Only queue items with equal votes can be reordered", 409);
      }
      if (beforeItem.id === item.id) return;
    }

    const sorted = this.sortedQueue();
    const tier = sorted.filter((candidate) => candidate.votes.size === item.votes.size);
    const tierOrderKeys = tier.map((candidate) => candidate.orderKey);
    const reorderedTier = tier.filter((candidate) => candidate.id !== item.id);
    const targetIndex = beforeItem
      ? reorderedTier.findIndex((candidate) => candidate.id === beforeItem.id)
      : reorderedTier.length;
    reorderedTier.splice(targetIndex, 0, item);
    reorderedTier.forEach((candidate, index) => {
      const orderKey = tierOrderKeys[index];
      if (orderKey !== undefined) candidate.orderKey = orderKey;
    });
    this.touch();
  }

  forcePlayNext(memberId: string, itemId: string): void {
    this.requireHost(memberId);
    if (!this.queue.has(itemId)) throw new RoomError("Queue item not found", 404);
    this.forcedNextItemId = itemId;
    this.touch();
  }

  requestPause(memberId: string, now = Date.now()): void {
    const member = this.requireMember(memberId);
    if (member.isHost) throw new RoomError("The host can pause playback directly", 409);
    if (!member.connected) throw new RoomError("Only connected members can request a pause", 409);
    if (!member.pauseVoteCapable) {
      throw new RoomError("This client does not support pause voting", 409);
    }
    if (this.pauseVote?.expiresAt !== undefined && this.pauseVote.expiresAt <= now) {
      this.expirePauseVote(now);
    }
    if (!this.playback.video || this.playback.status !== "playing") {
      throw new RoomError("Playback must be playing to request a pause", 409);
    }
    if (this.pauseVote && this.pauseVote.expiresAt > now) {
      throw new RoomError("A pause vote is already in progress", 409);
    }
    if (this.pauseVoteEligibleMembers().length <= 1) {
      this.pausePlayback(now, 0);
      this.touch(now);
      return;
    }
    this.pauseVote = {
      id: randomUUID(),
      requestedBy: member.id,
      requestedByName: member.displayName,
      votes: new Map(),
      startedAt: now,
      expiresAt: now + PAUSE_VOTE_TTL_MS,
    };
    this.touch(now);
  }

  castPauseVote(
    memberId: string,
    vote: PauseVoteChoice,
    pollId: string,
    now = Date.now(),
  ): boolean {
    const member = this.requireMember(memberId);
    if (!this.pauseVote || this.pauseVote.expiresAt <= now) {
      throw new RoomError("No pause vote is in progress", 409);
    }
    if (!member.connected) throw new RoomError("Only connected members can vote", 409);
    if (!member.pauseVoteCapable) {
      throw new RoomError("This client does not support pause voting", 409);
    }
    if (this.pauseVote.id !== pollId) {
      throw new RoomError("This pause vote is no longer active", 409);
    }
    if (!this.playback.video || this.playback.status !== "playing") {
      this.pauseVote = undefined;
      throw new RoomError("Playback is no longer playing", 409);
    }
    this.pauseVote.votes.set(memberId, vote);
    const paused = this.reconcilePauseVote(now);
    this.touch(now);
    return paused;
  }

  expirePauseVote(now = Date.now()): boolean {
    if (!this.pauseVote || this.pauseVote.expiresAt > now) return false;
    if (
      this.pauseVote.votes.size === 0 &&
      this.playback.video &&
      this.playback.status === "playing"
    ) {
      this.pausePlayback(now, 0);
    } else {
      this.pauseVote = undefined;
    }
    this.touch(now);
    return true;
  }

  playQueueItem(memberId: string, itemId: string, now = Date.now()): void {
    this.requireHost(memberId);
    const item = this.queue.get(itemId);
    if (!item) throw new RoomError("Queue item not found", 404);
    this.queue.delete(itemId);
    if (this.forcedNextItemId === itemId) this.forcedNextItemId = undefined;
    this.startQueueItem(item, now);
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
      this.pauseVote = undefined;
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
      this.pausePlayback(now);
      this.touch(now);
      return;
    }

    if (!Number.isFinite(positionMs) || positionMs! < 0 || positionMs! > MAX_POSITION_MS) {
      throw new RoomError("Seek position must be between 0 and 24 hours");
    }
    this.pauseVote = undefined;
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
    if (!this.playback.video) throw new RoomError("Nothing is playing", 409);
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
      songsAddedCount: candidate.songsAddedCount,
      chatMuted: this.mutedMemberIds.has(candidate.id),
    }));
    const queue: PublicQueueItem[] = this.sortedQueue().map((item) => ({
      id: item.id,
      video: item.video,
      addedBy: item.addedBy,
      addedByName: item.addedByName,
      addedAt: item.addedAt,
      voteCount: item.votes.size,
      votedByMe: item.votes.has(memberId),
      isForcedNext: item.id === this.forcedNextItemId,
    }));
    const connectedCount = members.filter((candidate) => candidate.connected).length;
    const pauseVote = this.publicPauseVoteFor(memberId, now);

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
      history: this.history,
      chat: this.chat,
      ...(pauseVote ? { pauseVote } : {}),
      skip: {
        votes: this.skipVotes.size,
        threshold: Math.max(1, Math.ceil(connectedCount / 2)),
        votedByMe: this.skipVotes.has(memberId),
      },
    };
  }

  private startQueueItem(item: QueueItem, now: number): void {
    this.archiveCurrentPlayback();
    this.skipVotes.clear();
    this.pauseVote = undefined;
    this.currentPlaybackItem = {
      id: item.id,
      video: item.video,
      addedBy: item.addedBy,
      addedByName: item.addedByName,
      addedAt: item.addedAt,
      playedAt: now,
    };
    this.playback = {
      video: item.video,
      status: "playing",
      positionMs: 0,
      anchorServerTimeMs: now + PLAY_LEAD_MS,
      revision: this.playback.revision + 1,
      addedBy: item.addedBy,
      addedByName: item.addedByName,
    };
  }

  private playNext(now: number): void {
    let item = this.forcedNextItemId
      ? this.queue.get(this.forcedNextItemId)
      : undefined;
    this.forcedNextItemId = undefined;
    item ??= this.sortedQueue()[0];
    if (!item) {
      this.archiveCurrentPlayback();
      this.skipVotes.clear();
      this.pauseVote = undefined;
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
    this.startQueueItem(item, now);
  }

  private sortedQueue(): QueueItem[] {
    return [...this.queue.values()].sort(
      (a, b) =>
        b.votes.size - a.votes.size ||
        a.orderKey - b.orderKey ||
        a.addedAt - b.addedAt ||
        a.id.localeCompare(b.id),
    );
  }

  private archiveCurrentPlayback(): void {
    if (!this.currentPlaybackItem) return;
    this.history.unshift(this.currentPlaybackItem);
    if (this.history.length > MAX_HISTORY_ITEMS) {
      this.history.length = MAX_HISTORY_ITEMS;
    }
    this.currentPlaybackItem = undefined;
  }

  private pausePlayback(now: number, leadMs = CONTROL_LEAD_MS): void {
    const effectiveAt = now + leadMs;
    this.playback = {
      ...this.playback,
      status: "paused",
      positionMs: this.positionAt(effectiveAt),
      anchorServerTimeMs: effectiveAt,
      revision: this.playback.revision + 1,
    };
    this.pauseVote = undefined;
  }

  private reconcilePauseVote(now: number): boolean {
    if (this.expirePauseVote(now) || !this.pauseVote) return false;
    const requester = this.members.get(this.pauseVote.requestedBy);
    if (!requester?.connected || !requester.pauseVoteCapable || requester.isHost) {
      this.pauseVote = undefined;
      return false;
    }
    for (const voterId of this.pauseVote.votes.keys()) {
      const voter = this.members.get(voterId);
      if (!voter?.connected || !voter.pauseVoteCapable) this.pauseVote.votes.delete(voterId);
    }
    const eligibleVoters = this.pauseVoteEligibleMembers().length;
    const yesVotes = [...this.pauseVote.votes.values()].filter(
      (vote) => vote === "yes",
    ).length;
    if (yesVotes >= pauseVoteThreshold(eligibleVoters)) {
      this.pausePlayback(now);
      return true;
    }
    if (this.pauseVote.votes.size >= eligibleVoters) {
      this.pauseVote = undefined;
    }
    return false;
  }

  private publicPauseVoteFor(
    memberId: string,
    now: number,
  ): PublicPauseVoteState | undefined {
    if (!this.pauseVote || this.pauseVote.expiresAt <= now) return undefined;
    const votes = [...this.pauseVote.votes.values()];
    const eligibleVoters = this.pauseVoteEligibleMembers().length;
    return {
      id: this.pauseVote.id,
      requestedBy: this.pauseVote.requestedBy,
      requestedByName: this.pauseVote.requestedByName,
      yesVotes: votes.filter((vote) => vote === "yes").length,
      noVotes: votes.filter((vote) => vote === "no").length,
      threshold: pauseVoteThreshold(eligibleVoters),
      eligibleVoters,
      myVote: this.pauseVote.votes.get(memberId),
      startedAt: this.pauseVote.startedAt,
      expiresAt: this.pauseVote.expiresAt,
    };
  }

  private pauseVoteEligibleMembers(): Member[] {
    return [...this.members.values()].filter(
      (member) => member.connected && member.pauseVoteCapable,
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
    this.onChange(this);
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

interface RoomManagerOptions {
  onRoomChanged?: (room: Room) => void;
  onRoomDeleted?: (code: string) => void;
}

export class RoomManager {
  private readonly rooms = new Map<string, Room>();

  constructor(private readonly options: RoomManagerOptions = {}) {}

  createRoom(displayName: string): { room: Room; member: Member } {
    let code: string;
    do code = randomBytes(3).toString("hex").toUpperCase();
    while (this.rooms.has(code));

    const room = new Room(code, this.options.onRoomChanged);
    const member = room.addMember(displayName, true);
    this.rooms.set(code, room);
    return { room, member };
  }

  getRoom(code: string): Room {
    const room = this.rooms.get(code.trim().toUpperCase());
    if (!room) throw new RoomError("Room not found", 404);
    return room;
  }

  activeRoomCount(): number {
    return this.rooms.size;
  }

  restoreRoom(value: unknown): Room {
    const room = Room.fromStoredState(value, this.options.onRoomChanged);
    if (this.rooms.has(room.code)) throw new RoomError("Stored room already exists", 409);
    this.rooms.set(room.code, room);
    return room;
  }

  pruneInactiveRooms(maxIdleMs: number, now = Date.now()): string[] {
    const removed: string[] = [];
    for (const [code, room] of this.rooms) {
      if (!room.hasConnectedMembers() && now - room.lastActivityAt >= maxIdleMs) {
        this.rooms.delete(code);
        this.options.onRoomDeleted?.(code);
        removed.push(code);
      }
    }
    return removed;
  }

  expirePauseVotes(now = Date.now()): string[] {
    const changedRooms: string[] = [];
    for (const [code, room] of this.rooms) {
      if (room.expirePauseVote(now)) changedRooms.push(code);
    }
    return changedRooms;
  }
}

function isStoredRoomState(value: unknown): value is StoredRoomStateV1 {
  if (!isRecord(value)) return false;
  const state = value as Partial<StoredRoomStateV1>;
  return state.version === 1 &&
    typeof state.code === "string" && /^[A-Z0-9]{6,8}$/.test(state.code) &&
    isNonNegativeSafeInteger(state.createdAt) &&
    isNonNegativeSafeInteger(state.lastActivityAt) &&
    state.lastActivityAt >= state.createdAt &&
    isBoundedUniqueArray(state.members, 50, isStoredMember, (member) => member.id) &&
    state.members.filter((member) => member.isHost).length <= 1 &&
    isBoundedUniqueArray(state.queue, MAX_QUEUE_ITEMS, isStoredQueueItem, (item) => item.id) &&
    isBoundedArray(state.history, MAX_HISTORY_ITEMS, isStoredHistoryItem) &&
    isBoundedUniqueArray(state.chat, MAX_CHAT_MESSAGES, isStoredChatMessage, (message) => message.id) &&
    isStoredPlaybackState(state.playback) &&
    (state.currentPlaybackItem === undefined || isStoredHistoryItem(state.currentPlaybackItem)) &&
    isConsistentStoredPlayback(state.playback, state.currentPlaybackItem) &&
    (state.forcedNextItemId === undefined ||
      (typeof state.forcedNextItemId === "string" &&
        state.queue.some((item) => item.id === state.forcedNextItemId))) &&
    isBoundedUniqueArray(state.mutedMemberIds, 50, isString, (memberId) => memberId) &&
    state.mutedMemberIds.every((memberId) => state.members!.some((member) => member.id === memberId)) &&
    isIncrementingCounter(state.nextOrderKey) &&
    state.queue.every((item) =>
      item.votes.every((memberId) => state.members!.some((member) => member.id === memberId))
    ) &&
    hasConsistentStoredReferences(state as StoredRoomStateV1);
}

function isStoredMember(value: unknown): value is Member {
  if (!isRecord(value)) return false;
  const member = value as Partial<Member>;
  return isBoundedString(member.id, 1, 100) &&
    isBoundedString(member.token, 1, 200) &&
    isBoundedString(member.displayName, 1, 40) &&
    typeof member.isHost === "boolean" &&
    typeof member.connected === "boolean" &&
    isNonNegativeSafeInteger(member.joinedAt) &&
    isIncrementingCounter(member.songsAddedCount) &&
    typeof member.pauseVoteCapable === "boolean";
}

function isStoredQueueItem(
  value: unknown,
): value is Omit<QueueItem, "votes"> & { votes: string[] } {
  if (!isRecord(value)) return false;
  const item = value as Partial<Omit<QueueItem, "votes"> & { votes: string[] }>;
  return isBoundedString(item.id, 1, 100) &&
    isStoredVideo(item.video) &&
    isBoundedString(item.addedBy, 1, 100) &&
    (item.addedByName === undefined || isBoundedString(item.addedByName, 1, 40)) &&
    isNonNegativeSafeInteger(item.addedAt) &&
    isIncrementingCounter(item.orderKey) &&
    isBoundedUniqueArray(item.votes, 50, isString, (memberId) => memberId);
}

function isStoredHistoryItem(value: unknown): value is HistoryItem {
  if (!isRecord(value)) return false;
  const item = value as Partial<HistoryItem>;
  return isBoundedString(item.id, 1, 100) &&
    isStoredVideo(item.video) &&
    isBoundedString(item.addedBy, 1, 100) &&
    (item.addedByName === undefined || isBoundedString(item.addedByName, 1, 40)) &&
    isNonNegativeSafeInteger(item.addedAt) &&
    isNonNegativeSafeInteger(item.playedAt);
}

function isStoredChatMessage(value: unknown): value is ChatMessage {
  if (!isRecord(value)) return false;
  const message = value as Partial<ChatMessage>;
  return isBoundedString(message.id, 1, 100) &&
    isBoundedString(message.memberId, 1, 100) &&
    isBoundedString(message.displayName, 1, 40) &&
    isBoundedString(message.text, 1, MAX_CHAT_MESSAGE_LENGTH) &&
    isNonNegativeSafeInteger(message.sentAt);
}

function isStoredPlaybackState(value: unknown): value is PlaybackState {
  if (!isRecord(value)) return false;
  const playback = value as Partial<PlaybackState>;
  return (playback.video === null || isStoredVideo(playback.video)) &&
    (playback.status === "idle" || playback.status === "playing" || playback.status === "paused") &&
    isNonNegativeSafeInteger(playback.positionMs) && playback.positionMs <= MAX_POSITION_MS &&
    isNonNegativeSafeInteger(playback.anchorServerTimeMs) &&
    isIncrementingCounter(playback.revision) &&
    (playback.addedBy === undefined || isBoundedString(playback.addedBy, 1, 100)) &&
    (playback.addedByName === undefined || isBoundedString(playback.addedByName, 1, 40));
}

function isConsistentStoredPlayback(
  playback: PlaybackState,
  currentItem: HistoryItem | undefined,
): boolean {
  if (playback.status === "idle") {
    return playback.video === null &&
      currentItem === undefined &&
      playback.addedBy === undefined &&
      playback.addedByName === undefined;
  }
  return playback.video !== null &&
    currentItem !== undefined &&
    sameVideo(currentItem.video, playback.video) &&
    currentItem.addedBy === playback.addedBy &&
    currentItem.addedByName === playback.addedByName;
}

function hasConsistentStoredReferences(state: StoredRoomStateV1): boolean {
  const queueItemIds = state.queue.map((item) => item.id);
  const historyItemIds = state.history.map((item) => item.id);
  const allArchivedAndQueuedIds = [...queueItemIds, ...historyItemIds];
  if (new Set(allArchivedAndQueuedIds).size !== allArchivedAndQueuedIds.length) return false;
  if (
    state.currentPlaybackItem &&
    allArchivedAndQueuedIds.includes(state.currentPlaybackItem.id)
  ) return false;

  const queueVideoIds = state.queue.map((item) => item.video.videoId);
  if (new Set(queueVideoIds).size !== queueVideoIds.length) return false;
  if (state.playback.video && queueVideoIds.includes(state.playback.video.videoId)) return false;

  const orderKeys = state.queue.map((item) => item.orderKey);
  if (new Set(orderKeys).size !== orderKeys.length) return false;
  return orderKeys.every((orderKey) => orderKey < state.nextOrderKey);
}

function sameVideo(left: VideoSummary, right: VideoSummary): boolean {
  return left.videoId === right.videoId &&
    left.title === right.title &&
    left.channelTitle === right.channelTitle &&
    left.thumbnailUrl === right.thumbnailUrl &&
    left.durationMs === right.durationMs;
}

function canonicalHistoryItem(item: HistoryItem): HistoryItem {
  return {
    id: item.id,
    video: validateVideo(item.video),
    addedBy: item.addedBy,
    ...(item.addedByName === undefined ? {} : { addedByName: item.addedByName }),
    addedAt: item.addedAt,
    playedAt: item.playedAt,
  };
}

function canonicalChatMessage(message: ChatMessage): ChatMessage {
  return {
    id: message.id,
    memberId: message.memberId,
    displayName: message.displayName,
    text: message.text,
    sentAt: message.sentAt,
  };
}

function canonicalPlaybackState(playback: PlaybackState): PlaybackState {
  return {
    video: playback.video ? validateVideo(playback.video) : null,
    status: playback.status,
    positionMs: playback.positionMs,
    anchorServerTimeMs: playback.anchorServerTimeMs,
    revision: playback.revision,
    ...(playback.addedBy === undefined ? {} : { addedBy: playback.addedBy }),
    ...(playback.addedByName === undefined ? {} : { addedByName: playback.addedByName }),
  };
}

function isStoredVideo(value: unknown): value is VideoSummary {
  if (!isRecord(value)) return false;
  const video = value as Partial<VideoSummary>;
  return typeof video.videoId === "string" && /^[A-Za-z0-9_-]{11}$/.test(video.videoId) &&
    isBoundedString(video.title, 1, 200) &&
    isBoundedString(video.channelTitle, 0, 100) &&
    isBoundedString(video.thumbnailUrl, 0, 500) &&
    (video.durationMs === undefined ||
      (isNonNegativeSafeInteger(video.durationMs) && video.durationMs <= MAX_POSITION_MS));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function isIncrementingCounter(value: unknown): value is number {
  return isNonNegativeSafeInteger(value) && value <= MAX_INCREMENTING_COUNTER;
}

function isBoundedString(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === "string" && value.length >= minimum && value.length <= maximum;
}

function isBoundedArray<T>(
  value: unknown,
  maximum: number,
  predicate: (item: unknown) => item is T,
): value is T[] {
  return Array.isArray(value) && value.length <= maximum && value.every(predicate);
}

function isBoundedUniqueArray<T>(
  value: unknown,
  maximum: number,
  predicate: (item: unknown) => item is T,
  key: (item: T) => string,
): value is T[] {
  if (!isBoundedArray(value, maximum, predicate)) return false;
  const keys = value.map(key);
  return new Set(keys).size === keys.length;
}

function isString(value: unknown): value is string {
  return typeof value === "string";
}

function pauseVoteThreshold(connectedCount: number): number {
  return Math.floor(connectedCount * 0.4) + 1;
}

function validateVideo(video: VideoSummary): VideoSummary {
  if (
    !isRecord(video) ||
    typeof video.videoId !== "string" ||
    !/^[A-Za-z0-9_-]{11}$/.test(video.videoId)
  ) {
    throw new RoomError("A valid YouTube video ID is required");
  }
  if (
    typeof video.title !== "string" ||
    typeof video.channelTitle !== "string" ||
    typeof video.thumbnailUrl !== "string" ||
    (video.durationMs !== undefined &&
      (typeof video.durationMs !== "number" ||
        !Number.isFinite(video.durationMs) ||
        video.durationMs < 0))
  ) {
    throw new RoomError("Valid YouTube video metadata is required");
  }
  const title = video.title.trim().slice(0, 200);
  if (!title) throw new RoomError("Video title is required");
  const cleanVideo: VideoSummary = {
    videoId: video.videoId,
    title,
    channelTitle: video.channelTitle.trim().slice(0, 100),
    thumbnailUrl: video.thumbnailUrl.trim().slice(0, 500),
  };
  if (video.durationMs !== undefined) {
    cleanVideo.durationMs = Math.round(Math.min(video.durationMs, MAX_POSITION_MS));
  }
  return cleanVideo;
}
