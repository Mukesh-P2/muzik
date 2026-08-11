export type PlaybackStatus = "idle" | "playing" | "paused";
export type PauseVoteChoice = "yes" | "no";

export interface VideoSummary {
  videoId: string;
  title: string;
  channelTitle: string;
  thumbnailUrl: string;
  durationMs?: number;
}

export interface Member {
  id: string;
  token: string;
  displayName: string;
  isHost: boolean;
  connected: boolean;
  joinedAt: number;
  songsAddedCount: number;
  pauseVoteCapable: boolean;
}

export interface QueueItem {
  id: string;
  video: VideoSummary;
  addedBy: string;
  addedByName?: string;
  addedAt: number;
  votes: Set<string>;
  orderKey: number;
}

export interface PlaybackState {
  video: VideoSummary | null;
  status: PlaybackStatus;
  positionMs: number;
  anchorServerTimeMs: number;
  revision: number;
  addedBy?: string;
  addedByName?: string;
}

export interface HistoryItem {
  id: string;
  video: VideoSummary;
  addedBy: string;
  addedByName?: string;
  addedAt: number;
  playedAt: number;
}

export interface ChatMessage {
  id: string;
  memberId: string;
  displayName: string;
  text: string;
  sentAt: number;
}

export interface PublicQueueItem {
  id: string;
  video: VideoSummary;
  addedBy: string;
  addedByName?: string;
  addedAt: number;
  voteCount: number;
  votedByMe: boolean;
  isForcedNext?: boolean;
}

export interface PublicPauseVoteState {
  id: string;
  requestedBy: string;
  requestedByName: string;
  yesVotes: number;
  noVotes: number;
  threshold: number;
  eligibleVoters: number;
  myVote?: PauseVoteChoice;
  startedAt: number;
  expiresAt: number;
}
