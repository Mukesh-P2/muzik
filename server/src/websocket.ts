import { Buffer } from "node:buffer";
import { WebSocket } from "ws";

// A maximally populated, valid room snapshot is larger than 1 MiB once JSON
// escaping is accounted for. Keep enough headroom for one such snapshot plus
// a small amount of already-buffered traffic, while still bounding slow peers.
export const MAX_OUTBOUND_BUFFERED_BYTES = 4 * 1024 * 1024;
export const MAX_INBOUND_MESSAGE_BYTES = 512 * 1024;

interface OutboundWebSocket {
  readonly readyState: number;
  readonly bufferedAmount: number;
  send(data: string): void;
  close(code: number, reason: string): void;
}

export function sendJsonWithBackpressure(
  socket: OutboundWebSocket,
  value: unknown,
  maxBufferedBytes = MAX_OUTBOUND_BUFFERED_BYTES,
): boolean {
  if (socket.readyState !== WebSocket.OPEN) return false;

  const payload = JSON.stringify(value);
  if (socket.bufferedAmount + Buffer.byteLength(payload, "utf8") > maxBufferedBytes) {
    socket.close(1013, "Outbound buffer limit exceeded");
    return false;
  }

  socket.send(payload);
  return true;
}
