use anyhow::{anyhow, Result};
use jni::{JNIEnv, objects::{JClass, JFloatArray, JObjectArray, JString}, sys::{jfloatArray, jlong, jstring}};
use std::{collections::HashMap, path::PathBuf, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicI64, Ordering}}};
use crate::media_models::MediaModel;

struct MediaSession { model: Mutex<MediaModel>, stopped: AtomicBool }
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<MediaSession>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn sessions() -> &'static Mutex<HashMap<i64, Arc<MediaSession>>> { SESSIONS.get_or_init(Default::default) }
fn session(id: i64) -> Result<Arc<MediaSession>> {
    sessions().lock().map_err(|_| anyhow!("Media registry unavailable"))?.get(&id).cloned().ok_or_else(|| anyhow!("Media model is closed"))
}
fn floats(env: &JNIEnv, array: &JFloatArray) -> Result<Vec<f32>> {
    let mut values = vec![0f32; env.get_array_length(array)? as usize];
    env.get_float_array_region(array, 0, &mut values)?;
    Ok(values)
}
fn output(env: &JNIEnv, values: &[f32]) -> Result<jfloatArray> {
    let result = env.new_float_array(i32::try_from(values.len())?)?;
    env.set_float_array_region(&result, 0, values)?;
    Ok(result.into_raw())
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_createMedia(mut env: JNIEnv, _: JClass, root: JString, weights: JObjectArray, task: JString) -> jlong {
    crate::guarded(&mut env, |env| {
        let root = PathBuf::from(env.get_string(&root)?.to_str()?);
        let task: String = env.get_string(&task)?.into();
        let mut paths = Vec::new();
        for index in 0..env.get_array_length(&weights)? {
            let path = JString::from(env.get_object_array_element(&weights, index)?);
            paths.push(PathBuf::from(env.get_string(&path)?.to_str()?));
        }
        let model = crate::cpu_pool().install(|| MediaModel::load(&root, &paths, &task))?;
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions().lock().map_err(|_| anyhow!("Media registry unavailable"))?.insert(id, Arc::new(MediaSession { model: Mutex::new(model), stopped: AtomicBool::new(false) }));
        Ok(id)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_classifyImage(mut env: JNIEnv, _: JClass, id: jlong, pixels: JFloatArray) -> jfloatArray {
    crate::guarded(&mut env, |env| {
        let shared = session(id)?;
        let pixels = floats(env, &pixels)?;
        let guard = shared.model.lock().map_err(|_| anyhow!("Media session unavailable"))?;
        let values = crate::cpu_pool().install(|| guard.classify(pixels, &shared.stopped))?;
        output(env, &values)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_transcribeAudio(mut env: JNIEnv, _: JClass, id: jlong, pcm: JFloatArray) -> jstring {
    crate::guarded(&mut env, |env| {
        let shared = session(id)?;
        let pcm = floats(env, &pcm)?;
        let mut guard = shared.model.lock().map_err(|_| anyhow!("Media session unavailable"))?;
        let model = &mut *guard;
        let text = crate::cpu_pool().install(|| model.transcribe(&pcm, &shared.stopped))?;
        Ok(env.new_string(text)?.into_raw())
    })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_generateImage(mut env: JNIEnv, _: JClass, id: jlong, prompt: JString) -> jfloatArray {
    crate::guarded(&mut env, |env| {
        let shared = session(id)?;
        let prompt: String = env.get_string(&prompt)?.into();
        let guard = shared.model.lock().map_err(|_| anyhow!("Media session unavailable"))?;
        let values = crate::cpu_pool().install(|| guard.generate_image(&prompt, &shared.stopped))?;
        output(env, &values)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_stopMedia(mut env: JNIEnv, _: JClass, id: jlong) {
    crate::guarded(&mut env, |_| { session(id)?.stopped.store(true, Ordering::Relaxed); Ok(()) })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_closeMedia(mut env: JNIEnv, _: JClass, id: jlong) {
    crate::guarded(&mut env, |_| { sessions().lock().map_err(|_| anyhow!("Media registry unavailable"))?.remove(&id); Ok(()) })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_resetMedia(mut env: JNIEnv, _: JClass, id: jlong) {
    crate::guarded(&mut env, |_| { session(id)?.stopped.store(false, Ordering::Relaxed); Ok(()) })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_whisperFeatures(mut env: JNIEnv, _: JClass, pcm: JFloatArray, mel_bins: i32) -> jfloatArray {
    crate::guarded(&mut env, |env| {
        anyhow::ensure!([80,128].contains(&mel_bins), "Unsupported Whisper mel bins");
        let mut pcm = floats(env, &pcm)?;
        anyhow::ensure!(!pcm.is_empty() && pcm.len() <= 480000, "Expected up to 30 seconds of 16 kHz mono PCM");
        pcm.resize(480000, 0.0);
        let filters = crate::media_models::mel_filters(mel_bins as usize);
        let mel = crate::cpu_pool().install(|| candle_transformers::models::whisper::audio::log_mel_spectrogram_(&pcm, &filters, 400, 160, mel_bins as usize, false));
        let frames = mel.len() / mel_bins as usize;
        let mut result = Vec::with_capacity(mel_bins as usize * 3000);
        for row in mel.chunks_exact(frames) { result.extend_from_slice(&row[..3000]); }
        output(env, &result)
    })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_diffusionStep(mut env: JNIEnv, _: JClass, latents: JFloatArray, prediction: JFloatArray, index: i32, v_prediction: jni::sys::jboolean) -> jfloatArray {
    crate::guarded(&mut env, |env| {
        use candle_core::{Tensor, Device};
        use candle_transformers::models::stable_diffusion::{ddim::DDIMSchedulerConfig, schedulers::{PredictionType, SchedulerConfig}};
        anyhow::ensure!((0..20).contains(&index), "Invalid diffusion step");
        let latents = floats(env, &latents)?;
        let prediction = floats(env, &prediction)?;
        let latents = Tensor::from_vec(latents, (1,4,32,32), &Device::Cpu)?;
        let prediction = Tensor::from_vec(prediction, (2,4,32,32), &Device::Cpu)?;
        let parts = prediction.chunk(2,0)?;
        let guided = (&parts[0] + ((&parts[1] - &parts[0])? * 7.5)?)?;
        let config = DDIMSchedulerConfig { prediction_type: if v_prediction != 0 { PredictionType::VPrediction } else { PredictionType::Epsilon }, ..Default::default() };
        let mut scheduler = config.build(20)?;
        let timestep = scheduler.timesteps()[index as usize];
        let result = scheduler.step(&guided, timestep, &latents)?.flatten_all()?.to_vec1::<f32>()?;
        output(env, &result)
    })
}
