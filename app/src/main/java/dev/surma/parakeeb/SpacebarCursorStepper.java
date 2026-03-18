package dev.surma.parakeeb;

final class SpacebarCursorStepper {
    private final float pixelsPerStep;
    private float lastX;
    private float accumulatedDelta;

    SpacebarCursorStepper(float pixelsPerStep) {
        this.pixelsPerStep = pixelsPerStep;
    }

    void start(float x) {
        lastX = x;
        accumulatedDelta = 0f;
    }

    int moveTo(float x) {
        float delta = x - lastX;
        lastX = x;
        accumulatedDelta += delta;

        int steps = (int) (accumulatedDelta / pixelsPerStep);
        accumulatedDelta -= steps * pixelsPerStep;
        return steps;
    }

    void reset() {
        accumulatedDelta = 0f;
    }
}
