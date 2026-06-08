import { renderHook, act } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { useStepNavigation } from "./useStepNavigation";

describe("useStepNavigation", () => {
  it("defaults to step 1", () => {
    const { result } = renderHook(() => useStepNavigation());
    expect(result.current.currentStep).toBe(1);
  });

  it("goStep navigates to valid step", () => {
    const { result } = renderHook(() => useStepNavigation());
    act(() => result.current.goStep(3));
    expect(result.current.currentStep).toBe(3);
  });

  it("goStep ignores out-of-range values", () => {
    const { result } = renderHook(() => useStepNavigation());
    act(() => result.current.goStep(0));
    expect(result.current.currentStep).toBe(1);
    act(() => result.current.goStep(7));
    expect(result.current.currentStep).toBe(1);
  });

  it("goNext increments step", () => {
    const { result } = renderHook(() => useStepNavigation());
    act(() => result.current.goNext());
    expect(result.current.currentStep).toBe(2);
  });

  it("goNext does not exceed 6", () => {
    const { result } = renderHook(() => useStepNavigation());
    act(() => result.current.goStep(6));
    act(() => result.current.goNext());
    expect(result.current.currentStep).toBe(6);
  });

  it("goPrev decrements step", () => {
    const { result } = renderHook(() => useStepNavigation());
    act(() => result.current.goStep(3));
    act(() => result.current.goPrev());
    expect(result.current.currentStep).toBe(2);
  });

  it("goPrev does not go below 1", () => {
    const { result } = renderHook(() => useStepNavigation());
    act(() => result.current.goPrev());
    expect(result.current.currentStep).toBe(1);
  });
});
