package dev.surma.parakeeb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VolumeDownActionResolverTest {
    @Test
    public void whileRecording_alwaysStopsRecording() {
        assertEquals(
                VolumeDownActionResolver.Action.STOP_RECORDING,
                VolumeDownActionResolver.resolve(true, false, false));
        assertEquals(
                VolumeDownActionResolver.Action.STOP_RECORDING,
                VolumeDownActionResolver.resolve(true, true, true));
    }

    @Test
    public void immediatelyAfterStopping_ignoresFurtherVolumeDownPresses() {
        assertEquals(
                VolumeDownActionResolver.Action.IGNORE,
                VolumeDownActionResolver.resolve(false, true, false));
        assertEquals(
                VolumeDownActionResolver.Action.IGNORE,
                VolumeDownActionResolver.resolve(false, true, true));
    }

    @Test
    public void idleSecondPressWithinGraceWindow_sends() {
        assertEquals(
                VolumeDownActionResolver.Action.SEND,
                VolumeDownActionResolver.resolve(false, false, true));
    }

    @Test
    public void idleFirstPress_startsGraceWindow() {
        assertEquals(
                VolumeDownActionResolver.Action.SCHEDULE_START,
                VolumeDownActionResolver.resolve(false, false, false));
    }
}
