export type PlaybackStatus = "idle" | "playing" | "paused";

export interface VideoSummary {
  videoId: string;
  title: string;
  channelTitle: string;
  thumbnailUrl: string;
}

export interface Member {
  id: string;
  token: string;
  displayName: string;
  isHost: boolean;
  connected: boolean;
  joinedAt: number;
}

export interface QueueItem {
  id: string;
  video: VideoSummary;
  addedBy: string;
  addedAt: number;
  votes: Set<string>;
}

export interface PlaybackState {
  video: VideoSummary | null;
  status: PlaybackStatus;
  positionMs: number;
  anchorServerTimeMs: number;
  revision: number;
}

export interface PublicQueueItem {
  id: string;
  video: VideoSummary;
  addedBy: string;
  addedAt: number;
  voteCount: number;
  votedByMe: boolean;
}
