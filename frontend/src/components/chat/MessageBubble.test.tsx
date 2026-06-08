import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { MessageBubble, type MessageData } from "./MessageBubble";

describe("MessageBubble", () => {
  it("renders user message content", () => {
    const msg: MessageData = { role: "user", content: "你好" };
    render(<MessageBubble message={msg} />);
    expect(screen.getByText("你好")).toBeInTheDocument();
  });

  it("renders assistant message content", () => {
    const msg: MessageData = { role: "assistant", content: "RAG 是检索增强生成" };
    render(<MessageBubble message={msg} />);
    expect(screen.getByText(/RAG 是检索增强生成/)).toBeInTheDocument();
  });

  it("shows loading skeleton when loading", () => {
    const msg: MessageData = { role: "assistant", content: "", loading: true };
    const { container } = render(<MessageBubble message={msg} />);
    expect(container.querySelector(".animate-pulse")).toBeInTheDocument();
  });

  it("shows error indicator when error", () => {
    const msg: MessageData = { role: "assistant", content: "", error: true };
    const { container } = render(<MessageBubble message={msg} />);
    // Error state shows AlertCircle icon (destructive color)
    expect(container.querySelector(".text-destructive")).toBeInTheDocument();
  });

  it("renders code block with syntax highlighting", () => {
    const msg: MessageData = {
      role: "assistant",
      content: '```python\nprint("hello")\n```',
    };
    render(<MessageBubble message={msg} />);
    expect(screen.getByText("python")).toBeInTheDocument();
    expect(screen.getByText(/print/)).toBeInTheDocument();
  });

  it("renders source references when present", () => {
    const msg: MessageData = {
      role: "assistant",
      content: "答案内容",
      sources: [
        { index: 1, documentId: "doc1", chunkIndex: 0, content: "来源片段", score: 0.85 },
      ],
    };
    render(<MessageBubble message={msg} />);
    expect(screen.getByText(/参考来源/)).toBeInTheDocument();
  });
});
