/**
 * SSE streaming parser — reads a ReadableStream<Uint8Array> from fetch,
 * yields text chunks as they arrive via Server-Sent Events.
 *
 * Backend expected format:
 *   data: {"content": "回答片段", "sources": [...]}
 *
 *   data: [DONE]  → stream end
 */
export interface SSEMessage {
  content?: string;
  sources?: Array<{
    index: number;
    documentId: string;
    chunkIndex: number;
    content: string;
    score: number;
  }>;
  done?: boolean;
  error?: string;
}

function getAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (typeof window !== "undefined") {
    const token = localStorage.getItem("edumerge_token");
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }
  return headers;
}

export async function* streamSSE(
  url: string,
  body: Record<string, string>
): AsyncGenerator<SSEMessage> {
  const response = await fetch(url, {
    method: "POST",
    headers: getAuthHeaders(),
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({ message: response.statusText }));
    throw new Error(err.message ?? "请求失败");
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error("浏览器不支持流式读取");

  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.startsWith("data:")) continue;

      const payload = trimmed.slice(5).trim();
      if (payload === "[DONE]") {
        yield { done: true };
        return;
      }

      try {
        const msg: SSEMessage = JSON.parse(payload);
        yield msg;
      } catch {
        // skip unparseable frames
      }
    }
  }
}
