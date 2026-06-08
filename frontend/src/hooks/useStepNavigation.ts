import { useState, useCallback } from "react";

const TOTAL_STEPS = 6;

export function useStepNavigation() {
  const [currentStep, setCurrentStep] = useState(1);

  const goStep = useCallback((s: number) => {
    if (s >= 1 && s <= TOTAL_STEPS) setCurrentStep(s);
  }, []);

  const goNext = useCallback(() => {
    setCurrentStep((prev) => Math.min(prev + 1, TOTAL_STEPS));
  }, []);

  const goPrev = useCallback(() => {
    setCurrentStep((prev) => Math.max(prev - 1, 1));
  }, []);

  return { currentStep, setCurrentStep, goStep, goNext, goPrev };
}
