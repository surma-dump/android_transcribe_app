package dev.notune.transcribe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpacebarCursorStepperTest {

    @Test
    public void move_returnsPositiveStepWhenMovingRightPastThreshold() {
        SpacebarCursorStepper stepper = new SpacebarCursorStepper(20f);
        stepper.start(100f);

        int steps = stepper.moveTo(125f);

        assertEquals(1, steps);
    }

    @Test
    public void move_accumulatesSmallMovementsUntilThresholdReached() {
        SpacebarCursorStepper stepper = new SpacebarCursorStepper(20f);
        stepper.start(100f);

        assertEquals(0, stepper.moveTo(109f));
        assertEquals(0, stepper.moveTo(118f));
        assertEquals(1, stepper.moveTo(121f));
    }

    @Test
    public void move_returnsNegativeStepWhenMovingLeftPastThreshold() {
        SpacebarCursorStepper stepper = new SpacebarCursorStepper(15f);
        stepper.start(200f);

        int steps = stepper.moveTo(160f);

        assertEquals(-2, steps);
    }

    @Test
    public void reset_clearsAccumulatedMovement() {
        SpacebarCursorStepper stepper = new SpacebarCursorStepper(20f);
        stepper.start(100f);
        assertEquals(0, stepper.moveTo(110f));

        stepper.reset();
        stepper.start(110f);

        assertEquals(0, stepper.moveTo(129f));
        assertEquals(1, stepper.moveTo(131f));
    }
}
