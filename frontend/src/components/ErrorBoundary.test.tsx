import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { ErrorBoundary } from "./ErrorBoundary";

function ThrowingChild() {
  throw new Error("测试错误");
}

function SafeChild() {
  return <div>安全内容</div>;
}

describe("ErrorBoundary", () => {
  it("renders children when no error", () => {
    render(
      <ErrorBoundary>
        <SafeChild />
      </ErrorBoundary>,
    );
    expect(screen.getByText("安全内容")).toBeInTheDocument();
  });

  it("renders error UI when child throws", () => {
    // Suppress console.error for expected error
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});

    render(
      <ErrorBoundary>
        <ThrowingChild />
      </ErrorBoundary>,
    );

    expect(screen.getByText("组件渲染出错")).toBeInTheDocument();
    expect(screen.getByText("测试错误")).toBeInTheDocument();
    expect(screen.getByText("重试")).toBeInTheDocument();

    spy.mockRestore();
  });

  it("renders custom fallback when provided", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});

    render(
      <ErrorBoundary fallback={<div>自定义兜底</div>}>
        <ThrowingChild />
      </ErrorBoundary>,
    );

    expect(screen.getByText("自定义兜底")).toBeInTheDocument();

    spy.mockRestore();
  });
});
