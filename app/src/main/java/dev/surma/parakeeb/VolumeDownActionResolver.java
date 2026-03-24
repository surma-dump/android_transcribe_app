package dev.surma.parakeeb;

final class VolumeDownActionResolver {
    enum Action {
        STOP_RECORDING,
        SEND,
        SCHEDULE_START,
        IGNORE
    }

    private VolumeDownActionResolver() {
    }

    static Action resolve(boolean isRecording, boolean postStopGuardActive, boolean startPending) {
        if (isRecording) {
            return Action.STOP_RECORDING;
        }
        if (postStopGuardActive) {
            return Action.IGNORE;
        }
        if (startPending) {
            return Action.SEND;
        }
        return Action.SCHEDULE_START;
    }
}
