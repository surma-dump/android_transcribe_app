use std::sync::{
    atomic::{AtomicUsize, Ordering},
    mpsc::{self, Sender},
    Arc, Mutex,
};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject};
use jni::JNIEnv;
use transcribe_rs::TranscriptionEngine;

use crate::engine;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
    pub transcription_tx: Sender<Vec<f32>>,
    pub pending_transcriptions: Arc<AtomicUsize>,
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

fn enqueue_status(pending_after_enqueue: usize) -> String {
    if pending_after_enqueue <= 1 {
        "Transcribing...".to_string()
    } else {
        format!(
            "Queued for transcription ({} ahead)",
            pending_after_enqueue - 1
        )
    }
}

fn completion_status(remaining_after_completion: usize) -> String {
    if remaining_after_completion == 0 {
        "Ready".to_string()
    } else {
        format!("Transcribing... ({} queued)", remaining_after_completion)
    }
}

fn spawn_transcription_worker(
    jvm: Arc<jni::JavaVM>,
    target_ref: GlobalRef,
    pending_transcriptions: Arc<AtomicUsize>,
    transcription_rx: mpsc::Receiver<Vec<f32>>,
) {
    std::thread::spawn(move || {
        for buffer in transcription_rx {
            let mut env = match jvm.attach_current_thread() {
                Ok(e) => e,
                Err(_) => {
                    pending_transcriptions.fetch_sub(1, Ordering::SeqCst);
                    continue;
                }
            };

            let obj = target_ref.as_obj();
            let mut had_error = false;

            // Wait for engine if somehow still loading
            if engine::get_engine().is_none() {
                if engine::ensure_loaded(&mut env, obj).is_err() {
                    had_error = true;
                }
            }

            if !had_error {
                if let Some(eng_arc) = engine::get_engine() {
                    let res = {
                        let mut eng = eng_arc.lock().unwrap();
                        eng.transcribe_samples(buffer, None)
                    };

                    match res {
                        Ok(r) => notify_text(&mut env, obj, &r.text),
                        Err(e) => {
                            had_error = true;
                            notify_status(&mut env, obj, &format!("Error: {}", e));
                        }
                    }
                } else {
                    had_error = true;
                    notify_status(&mut env, obj, "Error: model not loaded");
                }
            }

            let previous_pending = pending_transcriptions.fetch_sub(1, Ordering::SeqCst);
            let remaining = previous_pending.saturating_sub(1);

            if remaining > 0 {
                notify_status(&mut env, obj, &completion_status(remaining));
            } else if !had_error {
                notify_status(&mut env, obj, &completion_status(remaining));
            }
        }
    });
}

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let pending_transcriptions = Arc::new(AtomicUsize::new(0));
    let (transcription_tx, transcription_rx) = mpsc::channel::<Vec<f32>>();

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
        transcription_tx,
        pending_transcriptions: pending_transcriptions.clone(),
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();
    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    // Process queued transcriptions sequentially.
    spawn_transcription_worker(
        vm_arc.clone(),
        target_ref.clone(),
        pending_transcriptions,
        transcription_rx,
    );

    state
}

pub fn start_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    state.audio_buffer.lock().unwrap().clear();
    let buffer_clone = state.audio_buffer.clone();

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len() as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");
        }
        Err(e) => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Drop the stream to stop recording
    state.stream = None;

    let buffer = state.audio_buffer.lock().unwrap().clone();

    // Guard against empty buffer (mic permission denied, instant stop, etc.)
    if buffer.is_empty() {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        return;
    }

    let pending_after_enqueue = state.pending_transcriptions.fetch_add(1, Ordering::SeqCst) + 1;
    notify_status(
        &mut env,
        state.target_ref.as_obj(),
        &enqueue_status(pending_after_enqueue),
    );

    if state.transcription_tx.send(buffer).is_err() {
        let previous_pending = state.pending_transcriptions.fetch_sub(1, Ordering::SeqCst);
        let remaining = previous_pending.saturating_sub(1);
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: transcription queue unavailable",
        );
        if remaining > 0 {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &completion_status(remaining),
            );
        }
    }
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.stream = None;
    state.audio_buffer.lock().unwrap().clear();
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}

#[cfg(test)]
mod tests {
    use super::{completion_status, enqueue_status};

    #[test]
    fn enqueue_status_reports_transcribing_for_first_job() {
        assert_eq!(enqueue_status(1), "Transcribing...");
    }

    #[test]
    fn enqueue_status_reports_queue_depth_for_later_jobs() {
        assert_eq!(enqueue_status(2), "Queued for transcription (1 ahead)");
        assert_eq!(enqueue_status(4), "Queued for transcription (3 ahead)");
    }

    #[test]
    fn completion_status_reports_ready_when_queue_drains() {
        assert_eq!(completion_status(0), "Ready");
    }

    #[test]
    fn completion_status_reports_remaining_queue() {
        assert_eq!(completion_status(2), "Transcribing... (2 queued)");
    }
}
