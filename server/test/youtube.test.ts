import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  fetchYouTubeSearchResults,
  parseIso8601DurationMs,
  YouTubeSearchError,
} from "../src/youtube.js";

describe("YouTube durations", () => {
  it("parses valid ISO-8601 durations into milliseconds", () => {
    assert.equal(parseIso8601DurationMs("PT0S"), 0);
    assert.equal(parseIso8601DurationMs("PT45S"), 45_000);
    assert.equal(parseIso8601DurationMs("PT2M5S"), 125_000);
    assert.equal(parseIso8601DurationMs("PT1H2M3.5S"), 3_723_500);
    assert.equal(parseIso8601DurationMs("P1DT2H3M4S"), 93_784_000);
    assert.equal(parseIso8601DurationMs("P0D"), 0);
    assert.equal(parseIso8601DurationMs("PT1,5M"), 90_000);
  });

  it("rejects malformed, ambiguous, and overflowing durations", () => {
    for (const value of [
      undefined,
      42,
      "",
      "P",
      "PT",
      "P1DT",
      "P1M",
      "PT-1S",
      "PT1.5H30M",
      "PT1Sgarbage",
      `P${"9".repeat(100)}D`,
    ]) {
      assert.equal(parseIso8601DurationMs(value), undefined, String(value));
    }
  });
});

describe("YouTube search enrichment", () => {
  it("preserves search metadata and order while adding videos.list durations", async () => {
    const requested: URL[] = [];
    const signal = AbortSignal.timeout(5_000);
    const fetcher = async (input: string | URL, init?: RequestInit): Promise<Response> => {
      requested.push(new URL(input));
      if (requested.length === 1) {
        assert.equal(init?.signal, signal);
        return jsonResponse({
          items: [
            {
              id: { videoId: "aaaaaaaaaaa" },
              snippet: {
                title: "First &#127861; &amp; best",
                channelTitle: "Channel &#x1F3B5; &quot;A&quot;",
                thumbnails: { medium: { url: "https://example.test/medium.jpg" } },
              },
            },
            {
              id: { videoId: "bbbbbbbbbbb" },
              snippet: {
                title: "Second",
                channelTitle: "Channel B",
                thumbnails: { default: { url: "https://example.test/default.jpg" } },
              },
            },
          ],
        });
      }
      assert.notEqual(init?.signal, signal);
      assert.equal(init?.signal?.aborted, false);
      return jsonResponse({
        items: [
          { id: "bbbbbbbbbbb", contentDetails: { duration: "PT1H2M3.5S" } },
          { id: "aaaaaaaaaaa", contentDetails: { duration: "PT2M5S" } },
        ],
      });
    };

    const results = await fetchYouTubeSearchResults("test query", "test-api-key", {
      fetcher,
      signal,
    });

    assert.deepEqual(results, [
      {
        videoId: "aaaaaaaaaaa",
        title: "First 🍵 & best",
        channelTitle: 'Channel 🎵 "A"',
        thumbnailUrl: "https://example.test/medium.jpg",
        durationMs: 125_000,
      },
      {
        videoId: "bbbbbbbbbbb",
        title: "Second",
        channelTitle: "Channel B",
        thumbnailUrl: "https://example.test/default.jpg",
        durationMs: 3_723_500,
      },
    ]);
    assert.equal(requested.length, 2);
    assert.equal(requested[0]?.pathname, "/youtube/v3/search");
    assert.equal(requested[0]?.searchParams.get("q"), "test query");
    assert.equal(requested[0]?.searchParams.get("videoEmbeddable"), "true");
    assert.equal(requested[1]?.pathname, "/youtube/v3/videos");
    assert.equal(requested[1]?.searchParams.get("part"), "contentDetails");
    assert.equal(requested[1]?.searchParams.get("id"), "aaaaaaaaaaa,bbbbbbbbbbb");
  });

  it("returns compatible metadata when duration enrichment is unavailable", async () => {
    let calls = 0;
    const durationErrors: unknown[] = [];
    const results = await fetchYouTubeSearchResults("query", "test-api-key", {
      fetcher: async (): Promise<Response> => {
        calls += 1;
        if (calls === 1) {
          return jsonResponse({
            items: [{
              id: { videoId: "aaaaaaaaaaa" },
              snippet: { title: "Legacy-compatible", channelTitle: "Channel", thumbnails: {} },
            }],
          });
        }
        return new Response(null, { status: 503 });
      },
      onDurationError: (error) => durationErrors.push(error),
    });

    assert.deepEqual(results, [{
      videoId: "aaaaaaaaaaa",
      title: "Legacy-compatible",
      channelTitle: "Channel",
      thumbnailUrl: "",
    }]);
    assert.equal(calls, 2);
    assert.equal(durationErrors.length, 1);
  });

  it("returns base results when duration enrichment stalls past its own timeout", async () => {
    let calls = 0;
    let durationSignal: AbortSignal | undefined;
    const durationErrors: unknown[] = [];
    const results = await fetchYouTubeSearchResults("query", "test-api-key", {
      durationTimeoutMs: 10,
      fetcher: async (_input, init): Promise<Response> => {
        calls += 1;
        if (calls === 1) return searchResponse("aaaaaaaaaaa", "Available now");
        durationSignal = init?.signal ?? undefined;
        return new Promise<Response>(() => {});
      },
      onDurationError: (error) => durationErrors.push(error),
    });

    assert.deepEqual(results, [{
      videoId: "aaaaaaaaaaa",
      title: "Available now",
      channelTitle: "Channel",
      thumbnailUrl: "",
    }]);
    assert.equal(calls, 2);
    assert.equal(durationSignal?.aborted, true);
    assert.equal(durationErrors.length, 1);
  });

  it("returns base results when the shared timeout expires during enrichment", async () => {
    const controller = new AbortController();
    let calls = 0;
    let enrichmentStarted: (() => void) | undefined;
    const started = new Promise<void>((resolve) => { enrichmentStarted = resolve; });
    const resultsPromise = fetchYouTubeSearchResults("query", "test-api-key", {
      signal: controller.signal,
      durationTimeoutMs: 5_000,
      fetcher: async (): Promise<Response> => {
        calls += 1;
        if (calls === 1) return searchResponse("aaaaaaaaaaa", "Still available");
        enrichmentStarted?.();
        return new Promise<Response>(() => {});
      },
    });

    await started;
    controller.abort(new DOMException("The shared deadline elapsed", "TimeoutError"));

    assert.deepEqual(await resultsPromise, [{
      videoId: "aaaaaaaaaaa",
      title: "Still available",
      channelTitle: "Channel",
      thumbnailUrl: "",
    }]);
    assert.equal(calls, 2);
  });

  it("does not start enrichment after the shared deadline has already expired", async () => {
    const controller = new AbortController();
    let calls = 0;
    const results = await fetchYouTubeSearchResults("query", "test-api-key", {
      signal: controller.signal,
      fetcher: async (): Promise<Response> => {
        calls += 1;
        controller.abort(new DOMException("The shared deadline elapsed", "TimeoutError"));
        return searchResponse("aaaaaaaaaaa", "Still available");
      },
    });

    assert.deepEqual(results, [{
      videoId: "aaaaaaaaaaa",
      title: "Still available",
      channelTitle: "Channel",
      thumbnailUrl: "",
    }]);
    assert.equal(calls, 1);
  });

  it("distinguishes a primary request failure from optional enrichment", async () => {
    await assert.rejects(
      fetchYouTubeSearchResults("query", "test-api-key", {
        fetcher: async () => { throw new Error("offline"); },
      }),
      (error) => error instanceof YouTubeSearchError && error.kind === "unavailable",
    );
  });
});

function jsonResponse(value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function searchResponse(videoId: string, title: string): Response {
  return jsonResponse({
    items: [{
      id: { videoId },
      snippet: { title, channelTitle: "Channel", thumbnails: {} },
    }],
  });
}
