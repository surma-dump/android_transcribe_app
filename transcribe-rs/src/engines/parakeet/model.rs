use ndarray::{Array, Array1, Array2, Array3, ArrayD, ArrayViewD, IxDyn};
use once_cell::sync::Lazy;
use ort::execution_providers::{
    CPUExecutionProvider, ExecutionProvider, ExecutionProviderDispatch, NNAPIExecutionProvider,
    XNNPACKExecutionProvider,
};
use ort::inputs;
use ort::session::builder::GraphOptimizationLevel;
use ort::session::Session;
use ort::value::TensorRef;
use regex::Regex;

#[cfg(target_os = "android")]
use std::ffi::{CStr, CString};
use std::fs;
use std::path::Path;
use std::time::Instant;

pub type DecoderState = (Array3<f32>, Array3<f32>);

const SUBSAMPLING_FACTOR: usize = 8;
const WINDOW_SIZE: f32 = 0.01;
const MAX_TOKENS_PER_STEP: usize = 10;

/// Maximum audio chunk size in samples (16 kHz).
/// The encoder's positional encoding supports ~824 time-steps.
/// 60 seconds of audio ≈ 750 encoder frames, safely under the limit.
const MAX_CHUNK_SAMPLES: usize = 60 * 16_000; // 60 seconds

/// Overlap between consecutive chunks in samples (16 kHz).
/// 1 second of overlap so words at chunk boundaries aren't cut.
const CHUNK_OVERLAP_SAMPLES: usize = 1 * 16_000; // 1 second

static DECODE_SPACE_RE: Lazy<Result<Regex, regex::Error>> =
    Lazy::new(|| Regex::new(r"\A\s|\s\B|(\s)\b"));

const DEFAULT_EXECUTION_PROVIDERS: &str = "xnnpack,cpu";
#[cfg(target_os = "android")]
const ANDROID_EP_PROPERTY: &str = "debug.parakeeb.ort_eps";
#[cfg(target_os = "android")]
const ANDROID_OPT_PROPERTY: &str = "debug.parakeeb.ort_opt";

#[cfg(target_os = "android")]
fn android_system_property(name: &str) -> Option<String> {
    let name = CString::new(name).ok()?;
    let mut value = [0; 92]; // Android PROP_VALUE_MAX
    let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
    if len <= 0 {
        return None;
    }

    let value = unsafe { CStr::from_ptr(value.as_ptr()) }
        .to_string_lossy()
        .trim()
        .to_string();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

#[cfg(target_os = "android")]
extern "C" {
    fn __system_property_get(
        name: *const std::os::raw::c_char,
        value: *mut std::os::raw::c_char,
    ) -> std::os::raw::c_int;
}

#[derive(Debug, Clone)]
pub struct TimestampedResult {
    pub text: String,
    pub timestamps: Vec<f32>,
    pub tokens: Vec<String>,
}

#[derive(thiserror::Error, Debug)]
pub enum ParakeetError {
    #[error("ONNX Runtime error: {0}")]
    Ort(#[from] ort::Error),
    #[error("I/O error")]
    Io(#[from] std::io::Error),
    #[error("ndarray shape error")]
    Shape(#[from] ndarray::ShapeError),
    #[error("Model input not found: {0}")]
    InputNotFound(String),
    #[error("Model output not found: {0}")]
    OutputNotFound(String),
    #[error("Failed to get tensor shape for input: {0}")]
    TensorShape(String),
}

pub struct ParakeetModel {
    encoder: Session,
    decoder_joint: Session,
    preprocessor: Session,
    vocab: Vec<String>,
    blank_idx: i32,
    vocab_size: usize,
}

impl Drop for ParakeetModel {
    fn drop(&mut self) {
        log::debug!(
            "Dropping ParakeetModel with {} vocab tokens",
            self.vocab.len()
        );
    }
}

impl ParakeetModel {
    pub fn new<P: AsRef<Path>>(model_dir: P, quantized: bool) -> Result<Self, ParakeetError> {
        let encoder = Self::init_session(&model_dir, "encoder-model", None, quantized)?;
        let decoder_joint = Self::init_session(&model_dir, "decoder_joint-model", None, quantized)?;
        let preprocessor = Self::init_session(&model_dir, "nemo128", None, false)?;

        let (vocab, blank_idx) = Self::load_vocab(&model_dir)?;
        let vocab_size = vocab.len();

        log::info!(
            "Loaded vocabulary with {} tokens, blank_idx={}",
            vocab_size,
            blank_idx
        );

        Ok(Self {
            encoder,
            decoder_joint,
            preprocessor,
            vocab,
            blank_idx,
            vocab_size,
        })
    }

    fn init_session<P: AsRef<Path>>(
        model_dir: P,
        model_name: &str,
        intra_threads: Option<usize>,
        try_quantized: bool,
    ) -> Result<Session, ParakeetError> {
        let providers = Self::execution_providers();

        // Try quantized version first if requested, fallback to regular version
        let model_filename = if try_quantized {
            let quantized_name = format!("{}.int8.onnx", model_name);
            let quantized_path = model_dir.as_ref().join(&quantized_name);
            if quantized_path.exists() {
                log::info!("Loading quantized model from {}...", quantized_name);
                quantized_name
            } else {
                let regular_name = format!("{}.onnx", model_name);
                log::info!(
                    "Quantized model not found, loading regular model from {}...",
                    regular_name
                );
                regular_name
            }
        } else {
            let regular_name = format!("{}.onnx", model_name);
            log::info!("Loading model from {}...", regular_name);
            regular_name
        };

        log::info!(
            "Creating ONNX Runtime session for {} with requested execution providers: {:?}",
            model_filename,
            providers
        );

        let (optimization_level, optimization_source, optimization_name) =
            Self::requested_graph_optimization_level();
        log::info!(
            "Using ONNX Runtime graph optimization level from {}: {}",
            optimization_source,
            optimization_name
        );

        let session_start = Instant::now();
        let mut builder = Session::builder()?
            .with_optimization_level(optimization_level)?
            .with_execution_providers(providers)?
            .with_parallel_execution(true)?;

        if let Some(threads) = intra_threads {
            builder = builder
                .with_intra_threads(threads)?
                .with_inter_threads(threads)?;
        }

        let session = builder.commit_from_file(model_dir.as_ref().join(&model_filename))?;
        log::info!(
            "Created ONNX Runtime session for {} in {:.2}s",
            model_filename,
            session_start.elapsed().as_secs_f64()
        );

        for input in &session.inputs {
            log::info!(
                "Model '{}' input: name={}, type={:?}",
                model_filename,
                input.name,
                input.input_type
            );
        }

        Ok(session)
    }

    fn execution_providers() -> Vec<ExecutionProviderDispatch> {
        let (requested, source) = Self::requested_execution_provider_list();
        log::info!(
            "Requested ONNX Runtime execution providers from {}: {}",
            source,
            requested
        );

        let mut providers = Vec::new();
        let mut has_cpu = false;

        for token in requested.split(',') {
            let provider = token.trim();
            if provider.is_empty() {
                continue;
            }

            let provider = provider.to_ascii_lowercase();
            match provider.as_str() {
                "cpu" => {
                    let ep = CPUExecutionProvider::default();
                    Self::log_execution_provider_availability(&ep);
                    has_cpu = true;
                    providers.push(ep.build());
                }
                "xnnpack" => {
                    let ep = XNNPACKExecutionProvider::default();
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                "nnapi" => {
                    let ep = NNAPIExecutionProvider::default();
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                "nnapi-fp16" | "nnapi_fp16" => {
                    let ep = NNAPIExecutionProvider::default().with_fp16(true);
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                "nnapi-no-cpu" | "nnapi_disable_cpu" | "nnapi-disable-cpu" => {
                    let ep = NNAPIExecutionProvider::default().with_disable_cpu(true);
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                "nnapi-fp16-no-cpu" | "nnapi_fp16_no_cpu" | "nnapi-fp16-disable-cpu" => {
                    let ep = NNAPIExecutionProvider::default()
                        .with_fp16(true)
                        .with_disable_cpu(true);
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                "nnapi-nchw" => {
                    let ep = NNAPIExecutionProvider::default().with_nchw(true);
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                "nnapi-cpu-only" | "nnapi_cpu_only" => {
                    let ep = NNAPIExecutionProvider::default().with_cpu_only(true);
                    Self::log_execution_provider_availability(&ep);
                    providers.push(ep.build());
                }
                unknown => log::warn!(
                    "Ignoring unknown ONNX Runtime execution provider '{}'; expected one of cpu, xnnpack, nnapi, nnapi-fp16, nnapi-no-cpu, nnapi-fp16-no-cpu, nnapi-nchw, nnapi-cpu-only",
                    unknown
                ),
            }
        }

        if !has_cpu {
            log::info!("Appending CPUExecutionProvider as fallback");
            let ep = CPUExecutionProvider::default();
            Self::log_execution_provider_availability(&ep);
            providers.push(ep.build());
        }

        providers
    }

    fn log_execution_provider_availability(ep: &impl ExecutionProvider) {
        match ep.is_available() {
            Ok(available) => log::info!(
                "ONNX Runtime execution provider {}: supported_by_platform={}, is_available={}",
                ep.name(),
                ep.supported_by_platform(),
                available
            ),
            Err(err) => log::warn!(
                "Failed to query ONNX Runtime availability for {}: {}",
                ep.name(),
                err
            ),
        }
    }

    fn requested_execution_provider_list() -> (String, &'static str) {
        #[cfg(target_os = "android")]
        if let Some(value) = android_system_property(ANDROID_EP_PROPERTY) {
            return (value, ANDROID_EP_PROPERTY);
        }

        if let Ok(value) = std::env::var("PARAKEEB_ORT_EPS") {
            let value = value.trim();
            if !value.is_empty() {
                return (value.to_string(), "PARAKEEB_ORT_EPS");
            }
        }

        if let Some(value) = option_env!("PARAKEEB_ORT_EPS") {
            let value = value.trim();
            if !value.is_empty() {
                return (value.to_string(), "compile-time PARAKEEB_ORT_EPS");
            }
        }

        (DEFAULT_EXECUTION_PROVIDERS.to_string(), "default")
    }

    fn requested_graph_optimization_level() -> (GraphOptimizationLevel, &'static str, &'static str) {
        let (requested, source) = Self::requested_graph_optimization_level_name();
        let normalized = requested.trim().to_ascii_lowercase();
        match normalized.as_str() {
            "disable" | "disabled" | "none" | "0" => {
                (GraphOptimizationLevel::Disable, source, "disable")
            }
            "basic" | "level1" | "1" => (GraphOptimizationLevel::Level1, source, "basic"),
            "extended" | "level2" | "2" => (GraphOptimizationLevel::Level2, source, "extended"),
            "all" | "level3" | "3" => (GraphOptimizationLevel::Level3, source, "all"),
            unknown => {
                log::warn!(
                    "Ignoring unknown ONNX Runtime graph optimization level '{}'; expected disable, basic, extended, or all",
                    unknown
                );
                (GraphOptimizationLevel::Level3, "default", "all")
            }
        }
    }

    fn requested_graph_optimization_level_name() -> (String, &'static str) {
        #[cfg(target_os = "android")]
        if let Some(value) = android_system_property(ANDROID_OPT_PROPERTY) {
            return (value, ANDROID_OPT_PROPERTY);
        }

        if let Ok(value) = std::env::var("PARAKEEB_ORT_OPT") {
            let value = value.trim();
            if !value.is_empty() {
                return (value.to_string(), "PARAKEEB_ORT_OPT");
            }
        }

        if let Some(value) = option_env!("PARAKEEB_ORT_OPT") {
            let value = value.trim();
            if !value.is_empty() {
                return (value.to_string(), "compile-time PARAKEEB_ORT_OPT");
            }
        }

        ("all".to_string(), "default")
    }

    fn load_vocab<P: AsRef<Path>>(model_dir: P) -> Result<(Vec<String>, i32), ParakeetError> {
        let vocab_path = model_dir.as_ref().join("vocab.txt");
        let content = fs::read_to_string(vocab_path)?;

        let mut max_id = 0;
        let mut tokens_with_ids: Vec<(String, usize)> = Vec::new();
        let mut blank_idx: Option<usize> = None;

        for line in content.lines() {
            let parts: Vec<&str> = line.trim_end().split(' ').collect();
            if parts.len() >= 2 {
                let token = parts[0].to_string();
                if let Ok(id) = parts[1].parse::<usize>() {
                    if token == "<blk>" {
                        blank_idx = Some(id);
                    }
                    tokens_with_ids.push((token, id));
                    max_id = max_id.max(id);
                }
            }
        }

        // Create vocab vector with \u2581 replaced with space
        let mut vocab = vec![String::new(); max_id + 1];
        for (token, id) in tokens_with_ids {
            vocab[id] = token.replace('\u{2581}', " ");
        }

        let blank_idx = blank_idx.ok_or_else(|| {
            ParakeetError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "Missing <blk> token in vocabulary",
            ))
        })? as i32;

        Ok((vocab, blank_idx))
    }

    pub fn preprocess(
        &mut self,
        waveforms: &ArrayViewD<f32>,
        waveforms_lens: &ArrayViewD<i64>,
    ) -> Result<(ArrayD<f32>, ArrayD<i64>), ParakeetError> {
        log::trace!("Running preprocessor inference...");
        let inputs = inputs![
            "waveforms" => TensorRef::from_array_view(waveforms.view())?,
            "waveforms_lens" => TensorRef::from_array_view(waveforms_lens.view())?,
        ];
        let outputs = self.preprocessor.run(inputs)?;

        let features = outputs
            .get("features")
            .ok_or_else(|| ParakeetError::OutputNotFound("features".to_string()))?
            .try_extract_array()?;
        let features_lens = outputs
            .get("features_lens")
            .ok_or_else(|| ParakeetError::OutputNotFound("features_lens".to_string()))?
            .try_extract_array()?;

        Ok((features.to_owned(), features_lens.to_owned()))
    }

    pub fn encode(
        &mut self,
        audio_signal: &ArrayViewD<f32>,
        length: &ArrayViewD<i64>,
    ) -> Result<(ArrayD<f32>, ArrayD<i64>), ParakeetError> {
        log::trace!("Running encoder inference...");
        let inputs = inputs![
            "audio_signal" => TensorRef::from_array_view(audio_signal.view())?,
            "length" => TensorRef::from_array_view(length.view())?,
        ];
        let outputs = self.encoder.run(inputs)?;

        let encoder_output = outputs
            .get("outputs")
            .ok_or_else(|| ParakeetError::OutputNotFound("outputs".to_string()))?
            .try_extract_array()?;
        let encoded_lengths = outputs
            .get("encoded_lengths")
            .ok_or_else(|| ParakeetError::OutputNotFound("encoded_lengths".to_string()))?
            .try_extract_array()?;

        let encoder_output = encoder_output.permuted_axes(IxDyn(&[0, 2, 1]));

        Ok((encoder_output.to_owned(), encoded_lengths.to_owned()))
    }

    pub fn create_decoder_state(&self) -> Result<DecoderState, ParakeetError> {
        // Get input shapes from decoder model
        let inputs = &self.decoder_joint.inputs;

        let state1_shape = inputs
            .iter()
            .find(|input| input.name == "input_states_1")
            .ok_or_else(|| ParakeetError::InputNotFound("input_states_1".to_string()))?
            .input_type
            .tensor_shape()
            .ok_or_else(|| ParakeetError::TensorShape("input_states_1".to_string()))?;

        let state2_shape = inputs
            .iter()
            .find(|input| input.name == "input_states_2")
            .ok_or_else(|| ParakeetError::InputNotFound("input_states_2".to_string()))?
            .input_type
            .tensor_shape()
            .ok_or_else(|| ParakeetError::TensorShape("input_states_2".to_string()))?;

        // Create zero states with batch_size=1
        // Shape is [2, -1, 640] so we use [2, 1, 640] for batch_size=1
        let state1 = Array::zeros((
            state1_shape[0] as usize,
            1, // batch_size = 1
            state1_shape[2] as usize,
        ));

        let state2 = Array::zeros((
            state2_shape[0] as usize,
            1, // batch_size = 1
            state2_shape[2] as usize,
        ));

        Ok((state1, state2))
    }

    pub fn decode_step(
        &mut self,
        prev_tokens: &[i32],
        prev_state: &DecoderState,
        encoder_out: &ArrayViewD<f32>, // [time_steps, 1024]
    ) -> Result<(ArrayD<f32>, DecoderState), ParakeetError> {
        log::trace!("Running decoder inference...");

        // Get last token or blank_idx if empty
        let target_token = prev_tokens.last().copied().unwrap_or(self.blank_idx);

        // Prepare inputs matching Python: encoder_out[None, :, None] -> [1, time_steps, 1]
        let encoder_outputs = encoder_out
            .to_owned()
            .insert_axis(ndarray::Axis(0))
            .insert_axis(ndarray::Axis(2));
        let targets = Array2::from_shape_vec((1, 1), vec![target_token])?;
        let target_length = Array1::from_vec(vec![1]);

        let inputs = inputs![
            "encoder_outputs" => TensorRef::from_array_view(encoder_outputs.view())?,
            "targets" => TensorRef::from_array_view(targets.view())?,
            "target_length" => TensorRef::from_array_view(target_length.view())?,
            "input_states_1" => TensorRef::from_array_view(prev_state.0.view())?,
            "input_states_2" => TensorRef::from_array_view(prev_state.1.view())?,
        ];

        let outputs = self.decoder_joint.run(inputs)?;

        let logits = outputs
            .get("outputs")
            .ok_or_else(|| ParakeetError::OutputNotFound("outputs".to_string()))?
            .try_extract_array()?;
        log::trace!(
            "Logits shape: {:?}, vocab_size: {}",
            logits.shape(),
            self.vocab_size
        );
        let state1 = outputs
            .get("output_states_1")
            .ok_or_else(|| ParakeetError::OutputNotFound("output_states_1".to_string()))?
            .try_extract_array()?;
        let state2 = outputs
            .get("output_states_2")
            .ok_or_else(|| ParakeetError::OutputNotFound("output_states_2".to_string()))?
            .try_extract_array()?;

        // Squeeze outputs like Python (remove batch dimension)
        let logits = logits.remove_axis(ndarray::Axis(0));

        // Convert ArrayD back to Array3 to match expected return type
        let state1_3d = state1.to_owned().into_dimensionality::<ndarray::Ix3>()?;
        let state2_3d = state2.to_owned().into_dimensionality::<ndarray::Ix3>()?;

        Ok((logits.to_owned(), (state1_3d, state2_3d)))
    }

    pub fn recognize_batch(
        &mut self,
        waveforms: &ArrayViewD<f32>,
        waveforms_len: &ArrayViewD<i64>,
    ) -> Result<Vec<TimestampedResult>, ParakeetError> {
        let recognize_start = Instant::now();

        // Preprocess and encode
        let preprocess_start = Instant::now();
        let (features, features_lens) = self.preprocess(waveforms, waveforms_len)?;
        log::info!(
            "Parakeet preprocessor inference completed in {:.2}s",
            preprocess_start.elapsed().as_secs_f64()
        );

        let encode_start = Instant::now();
        let (encoder_out, encoder_out_lens) =
            self.encode(&features.view(), &features_lens.view())?;
        log::info!(
            "Parakeet encoder inference completed in {:.2}s",
            encode_start.elapsed().as_secs_f64()
        );

        // Decode for each batch item
        let mut results = Vec::new();
        for (index, (encodings, &encodings_len)) in encoder_out
            .outer_iter()
            .zip(encoder_out_lens.iter())
            .enumerate()
        {
            let decode_start = Instant::now();
            let (tokens, timestamps) =
                self.decode_sequence(&encodings.view(), encodings_len as usize)?;
            log::info!(
                "Parakeet decoder inference for batch item {} completed in {:.2}s ({} encoder frames, {} tokens)",
                index,
                decode_start.elapsed().as_secs_f64(),
                encodings_len,
                tokens.len()
            );
            let result = self.decode_tokens(tokens, timestamps);
            results.push(result);
        }

        log::info!(
            "Parakeet recognize_batch completed in {:.2}s",
            recognize_start.elapsed().as_secs_f64()
        );

        Ok(results)
    }

    fn decode_sequence(
        &mut self,
        encodings: &ArrayViewD<f32>, // [time_steps, 1024]
        encodings_len: usize,
    ) -> Result<(Vec<i32>, Vec<usize>), ParakeetError> {
        let mut prev_state = self.create_decoder_state()?;
        let mut tokens = Vec::new();
        let mut timestamps = Vec::new();

        let mut t = 0;
        let mut emitted_tokens = 0;

        while t < encodings_len {
            let encoder_step = encodings.slice(ndarray::s![t, ..]);
            // Convert to dynamic dimension to match decode_step parameter type
            let encoder_step_dyn = encoder_step.to_owned().into_dyn();
            let (probs, new_state) =
                self.decode_step(&tokens, &prev_state, &encoder_step_dyn.view())?;

            // For TDT models, split output into vocab logits and duration logits
            // output[:vocab_size] = vocabulary logits
            // output[vocab_size:] = duration logits
            let vocab_logits_slice = probs.as_slice().ok_or_else(|| {
                ParakeetError::Shape(ndarray::ShapeError::from_kind(
                    ndarray::ErrorKind::IncompatibleShape,
                ))
            })?;

            let vocab_logits = if probs.len() > self.vocab_size {
                // TDT model - extract only vocabulary logits
                log::trace!(
                    "TDT model detected: splitting {} logits into vocab({}) + duration",
                    probs.len(),
                    self.vocab_size
                );
                &vocab_logits_slice[..self.vocab_size]
            } else {
                // Regular RNN-T model
                vocab_logits_slice
            };

            // Get argmax token from vocabulary logits only
            let token = vocab_logits
                .iter()
                .enumerate()
                .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
                .map(|(idx, _)| idx as i32)
                .unwrap_or(self.blank_idx);

            if token != self.blank_idx {
                prev_state = new_state;
                tokens.push(token);
                timestamps.push(t);
                emitted_tokens += 1;
            }

            // Step logic from Python - simplified since step is always -1
            if token == self.blank_idx || emitted_tokens == MAX_TOKENS_PER_STEP {
                t += 1;
                emitted_tokens = 0;
            }
        }

        Ok((tokens, timestamps))
    }

    fn decode_tokens(&self, ids: Vec<i32>, timestamps: Vec<usize>) -> TimestampedResult {
        let tokens: Vec<String> = ids
            .iter()
            .filter_map(|&id| {
                let idx = id as usize;
                if idx < self.vocab.len() {
                    Some(self.vocab[idx].clone())
                } else {
                    None
                }
            })
            .collect();

        let text = match &*DECODE_SPACE_RE {
            Ok(regex) => regex
                .replace_all(&tokens.join(""), |caps: &regex::Captures| {
                    if caps.get(1).is_some() {
                        " "
                    } else {
                        ""
                    }
                })
                .to_string(),
            Err(_) => tokens.join(""), // Fallback if regex failed to compile
        };

        let float_timestamps: Vec<f32> = timestamps
            .iter()
            .map(|&t| WINDOW_SIZE * SUBSAMPLING_FACTOR as f32 * t as f32)
            .collect();

        TimestampedResult {
            text,
            timestamps: float_timestamps,
            tokens,
        }
    }

    /// Transcribe a single chunk that fits within the encoder's positional
    /// encoding limit.
    fn transcribe_chunk(&mut self, samples: Vec<f32>) -> Result<TimestampedResult, ParakeetError> {
        let chunk_start = Instant::now();
        let batch_size = 1;
        let samples_len = samples.len();
        log::info!(
            "Transcribing Parakeet chunk: {} samples ({:.2}s)",
            samples_len,
            samples_len as f64 / 16_000.0
        );

        let waveforms = Array2::from_shape_vec((batch_size, samples_len), samples)?.into_dyn();
        let waveforms_lens = Array1::from_vec(vec![samples_len as i64]).into_dyn();

        let results = self.recognize_batch(&waveforms.view(), &waveforms_lens.view())?;
        log::info!(
            "Parakeet chunk transcription completed in {:.2}s",
            chunk_start.elapsed().as_secs_f64()
        );

        results.into_iter().next().ok_or_else(|| {
            ParakeetError::Io(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "No transcription result returned",
            ))
        })
    }

    pub fn transcribe_samples(
        &mut self,
        samples: Vec<f32>,
    ) -> Result<TimestampedResult, ParakeetError> {
        // Guard against empty audio — ORT crashes on zero-length inputs
        if samples.is_empty() {
            return Ok(TimestampedResult {
                text: String::new(),
                timestamps: Vec::new(),
                tokens: Vec::new(),
            });
        }

        // Short audio: process in a single pass (no chunking overhead)
        if samples.len() <= MAX_CHUNK_SAMPLES {
            return self.transcribe_chunk(samples);
        }

        // Long audio: split into overlapping chunks to stay within the
        // encoder's maximum positional-encoding length.
        log::info!(
            "Audio has {} samples ({:.1}s), chunking into ≤{:.0}s segments",
            samples.len(),
            samples.len() as f64 / 16_000.0,
            MAX_CHUNK_SAMPLES as f64 / 16_000.0,
        );

        let step = MAX_CHUNK_SAMPLES - CHUNK_OVERLAP_SAMPLES;
        let mut merged_text = String::new();
        let mut merged_tokens: Vec<String> = Vec::new();
        let mut merged_timestamps: Vec<f32> = Vec::new();

        let mut offset: usize = 0;
        while offset < samples.len() {
            let end = (offset + MAX_CHUNK_SAMPLES).min(samples.len());
            let chunk = samples[offset..end].to_vec();
            let chunk_time_offset = offset as f32 / 16_000.0;

            log::info!(
                "Processing chunk at {:.1}s–{:.1}s",
                chunk_time_offset,
                end as f32 / 16_000.0,
            );

            let result = self.transcribe_chunk(chunk)?;

            if !result.text.is_empty() {
                // For chunks after the first one we need to trim the overlap
                // region to avoid duplicating words at the boundary.
                if offset > 0 && !result.timestamps.is_empty() {
                    let overlap_time = CHUNK_OVERLAP_SAMPLES as f32 / 16_000.0;
                    // Find the first token whose timestamp is past the overlap
                    let skip = result
                        .timestamps
                        .iter()
                        .position(|&t| t >= overlap_time)
                        .unwrap_or(0);

                    if skip < result.tokens.len() {
                        if !merged_text.is_empty() {
                            merged_text.push(' ');
                        }
                        // Reconstruct text from the kept tokens
                        let kept_tokens = &result.tokens[skip..];
                        let kept_text: String = kept_tokens.join("");
                        // Clean leading space from subword tokens
                        merged_text.push_str(kept_text.trim_start());

                        for (token, &ts) in result.tokens[skip..]
                            .iter()
                            .zip(result.timestamps[skip..].iter())
                        {
                            merged_tokens.push(token.clone());
                            merged_timestamps.push(ts + chunk_time_offset);
                        }
                    }
                } else {
                    // First chunk — take everything
                    merged_text.push_str(&result.text);
                    for (token, &ts) in result.tokens.iter().zip(result.timestamps.iter()) {
                        merged_tokens.push(token.clone());
                        merged_timestamps.push(ts + chunk_time_offset);
                    }
                }
            }

            if end >= samples.len() {
                break;
            }
            offset += step;
        }

        Ok(TimestampedResult {
            text: merged_text,
            timestamps: merged_timestamps,
            tokens: merged_tokens,
        })
    }
}
