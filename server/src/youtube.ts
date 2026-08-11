import type { VideoSummary } from "./types.js";

type Fetcher = (input: string | URL, init?: RequestInit) => Promise<Response>;

interface YouTubeSearchOptions {
  fetcher?: Fetcher;
  signal?: AbortSignal;
  durationTimeoutMs?: number;
  onDurationError?: (error: unknown) => void;
}

const DURATION_ENRICHMENT_TIMEOUT_MS = 2_000;

export class YouTubeSearchError extends Error {
  constructor(
    readonly kind: "unavailable" | "failed",
    readonly originalError?: unknown,
  ) {
    super(kind === "unavailable" ? "YouTube search request failed" : "YouTube search failed");
    this.name = "YouTubeSearchError";
  }
}

export async function fetchYouTubeSearchResults(
  query: string,
  apiKey: string,
  options: YouTubeSearchOptions = {},
): Promise<VideoSummary[]> {
  const fetcher = options.fetcher ?? fetch;
  const signal = options.signal ?? AbortSignal.timeout(10_000);
  const searchUrl = new URL("https://www.googleapis.com/youtube/v3/search");
  searchUrl.search = new URLSearchParams({
    key: apiKey,
    part: "snippet",
    type: "video",
    videoEmbeddable: "true",
    maxResults: "15",
    q: query,
  }).toString();

  let searchResponse: Response;
  try {
    searchResponse = await fetcher(searchUrl, { signal });
  } catch (error) {
    throw new YouTubeSearchError("unavailable", error);
  }
  if (!searchResponse.ok) throw new YouTubeSearchError("failed");

  let searchBody: unknown;
  try {
    searchBody = await searchResponse.json();
  } catch (error) {
    throw new YouTubeSearchError("failed", error);
  }
  const results = parseSearchResults(searchBody);
  const videoIds = [...new Set(results.map((result) => result.videoId))];
  if (videoIds.length === 0) return results;

  const videosUrl = new URL("https://www.googleapis.com/youtube/v3/videos");
  videosUrl.search = new URLSearchParams({
    key: apiKey,
    part: "contentDetails",
    id: videoIds.join(","),
  }).toString();

  const durationController = new AbortController();
  const abortDuration = () => durationController.abort(signal.reason);
  if (signal.aborted) abortDuration();
  else signal.addEventListener("abort", abortDuration, { once: true });
  const durationTimeout = setTimeout(() => {
    durationController.abort(new DOMException("YouTube duration enrichment timed out", "TimeoutError"));
  }, options.durationTimeoutMs ?? DURATION_ENRICHMENT_TIMEOUT_MS);
  const durationSignal = durationController.signal;
  try {
    if (durationSignal.aborted) throw abortReason(durationSignal);
    const videosResponse = await raceWithSignal(
      fetcher(videosUrl, { signal: durationSignal }),
      durationSignal,
    );
    if (!videosResponse.ok) {
      reportDurationError(options, new Error(`YouTube videos request returned ${videosResponse.status}`));
      return results;
    }
    const durations = parseDurationResponse(await raceWithSignal(videosResponse.json(), durationSignal));
    return results.map((result) => {
      const durationMs = durations.get(result.videoId);
      return durationMs === undefined ? result : { ...result, durationMs };
    });
  } catch (error) {
    reportDurationError(options, error);
    return results;
  } finally {
    clearTimeout(durationTimeout);
    signal.removeEventListener("abort", abortDuration);
  }
}

export function parseIso8601DurationMs(value: unknown): number | undefined {
  if (typeof value !== "string") return undefined;
  const match = /^P(?:(\d+(?:[.,]\d+)?)D)?(?:T(?:(\d+(?:[.,]\d+)?)H)?(?:(\d+(?:[.,]\d+)?)M)?(?:(\d+(?:[.,]\d+)?)S)?)?$/i.exec(value);
  if (!match || match.slice(1).every((part) => part === undefined)) return undefined;
  const parts = match.slice(1);
  if (/T/i.test(value) && parts.slice(1).every((part) => part === undefined)) return undefined;
  const fractionalParts = parts
    .map((part, index) => part?.match(/[.,]/) ? index : -1)
    .filter((index) => index >= 0);
  if (fractionalParts.length > 1) return undefined;
  if (fractionalParts[0] !== undefined && parts.slice(fractionalParts[0] + 1).some(Boolean)) {
    return undefined;
  }

  const [days, hours, minutes, seconds] = parts.map((part) =>
    part === undefined ? 0 : Number(part.replace(",", "."))
  );
  const durationMs = Math.round(
    ((days! * 24 * 60 * 60) + (hours! * 60 * 60) + (minutes! * 60) + seconds!) * 1_000,
  );
  return Number.isSafeInteger(durationMs) && durationMs >= 0 ? durationMs : undefined;
}

function parseSearchResults(value: unknown): VideoSummary[] {
  const body = objectValue(value);
  const items = Array.isArray(body?.items) ? body.items : [];
  return items.flatMap((value) => {
    const item = objectValue(value);
    const id = objectValue(item?.id);
    const snippet = objectValue(item?.snippet);
    const videoId = id?.videoId;
    const title = snippet?.title;
    if (typeof videoId !== "string" || typeof title !== "string" || !title) return [];

    const thumbnails = objectValue(snippet.thumbnails);
    const medium = objectValue(thumbnails?.medium);
    const fallback = objectValue(thumbnails?.default);
    return [{
      videoId,
      title: decodeEntities(title),
      channelTitle: decodeEntities(typeof snippet.channelTitle === "string" ? snippet.channelTitle : ""),
      thumbnailUrl: typeof medium?.url === "string"
        ? medium.url
        : typeof fallback?.url === "string" ? fallback.url : "",
    }];
  });
}

function parseDurationResponse(value: unknown): Map<string, number> {
  const body = objectValue(value);
  const items = Array.isArray(body?.items) ? body.items : [];
  const durations = new Map<string, number>();
  for (const value of items) {
    const item = objectValue(value);
    const contentDetails = objectValue(item?.contentDetails);
    const durationMs = parseIso8601DurationMs(contentDetails?.duration);
    if (typeof item?.id === "string" && durationMs !== undefined) {
      durations.set(item.id, durationMs);
    }
  }
  return durations;
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

async function raceWithSignal<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  let onAbort: (() => void) | undefined;
  const aborted = new Promise<never>((_resolve, reject) => {
    if (signal.aborted) {
      reject(abortReason(signal));
      return;
    }
    onAbort = () => reject(abortReason(signal));
    signal.addEventListener("abort", onAbort, { once: true });
  });
  try {
    return await Promise.race([promise, aborted]);
  } finally {
    if (onAbort) signal.removeEventListener("abort", onAbort);
  }
}

function abortReason(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException("The operation was aborted", "AbortError");
}

function reportDurationError(options: YouTubeSearchOptions, error: unknown): void {
  try {
    options.onDurationError?.(error);
  } catch {
    // Duration metadata is optional and must not make otherwise valid search results fail.
  }
}

function decodeEntities(value: string): string {
  return value.replace(
    /&(#(?:x[0-9a-f]+|\d+)|amp|quot|apos|lt|gt|nbsp);/gi,
    (entity, body: string) => {
      const normalized = body.toLowerCase();
      const named = {
        amp: "&",
        quot: '"',
        apos: "'",
        lt: "<",
        gt: ">",
        nbsp: "\u00a0",
      } as const;
      if (normalized in named) return named[normalized as keyof typeof named];

      const codePoint = normalized.startsWith("#x")
        ? Number.parseInt(normalized.slice(2), 16)
        : Number.parseInt(normalized.slice(1), 10);
      if (
        !Number.isInteger(codePoint) ||
        codePoint <= 0 ||
        codePoint > 0x10ffff ||
        (codePoint >= 0xd800 && codePoint <= 0xdfff)
      ) {
        return entity;
      }
      return String.fromCodePoint(codePoint);
    },
  );
}
