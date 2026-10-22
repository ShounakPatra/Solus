use anyhow::{anyhow, bail, Result};
use candle_core::{DType, Device, Tensor};
use candle_nn::VarBuilder;
mod gpt2;
mod media_models;
mod media_sessions;
mod tokenizer_support;
mod text_models;
use text_models::TextModel;
use jni::{JNIEnv, objects::{JClass, JIntArray, JObjectArray, JString}, sys::{jfloatArray, jintArray, jlong, jstring}};
use std::{collections::HashMap, path::PathBuf, sync::{Arc, Mutex, OnceLock, atomic::{AtomicI64, Ordering}}};
use tokenizers::Tokenizer;

struct Session { tokenizer: Tokenizer, model: Option<TextModel> }
type Shared = Arc<Mutex<Session>>;
static SESSIONS: OnceLock<Mutex<HashMap<i64, Shared>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
static CPU_POOL: OnceLock<rayon::ThreadPool> = OnceLock::new();
fn cpu_pool() -> &'static rayon::ThreadPool {
    CPU_POOL.get_or_init(|| rayon::ThreadPoolBuilder::new().num_threads(2).build().expect("Candle CPU pool"))
}
fn sessions() -> &'static Mutex<HashMap<i64, Shared>> { SESSIONS.get_or_init(Default::default) }
fn session(id: i64) -> Result<Shared> {
    sessions().lock().map_err(|_| anyhow!("Session registry unavailable"))?.get(&id).cloned().ok_or_else(|| anyhow!("Model session is closed"))
}
// No Rust panic or error may unwind into the VM. Registry handles avoid dangling raw pointers.
fn guarded<T: Default>(env: &mut JNIEnv, operation: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| operation(env))) {
        Ok(Ok(value)) => value,
        result => {
            let message = match result { Ok(Err(e)) => format!("{e:#}"), _ => "Candle runtime panicked".into() };
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            T::default()
        }
    }
}
fn ids(env: &JNIEnv, array: &JIntArray) -> Result<Vec<u32>> {
    let mut values = vec![0i32; env.get_array_length(array)? as usize];
    env.get_int_array_region(array, 0, &mut values)?;
    values.into_iter().map(|v| u32::try_from(v).map_err(Into::into)).collect()
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_create(mut env: JNIEnv, _: JClass, directory: JString, weights: JObjectArray, context_tokens: i32) -> jlong {
    guarded(&mut env, |env| {
        let directory = PathBuf::from(env.get_string(&directory)?.to_str()?);
        let clip = directory.file_name().and_then(|v| v.to_str()) == Some("tokenizer");
        let tokenizer = tokenizer_support::load(&directory, clip)?;
        let mut paths = Vec::new();
        for i in 0..env.get_array_length(&weights)? {
            let name = JString::from(env.get_object_array_element(&weights, i)?);
            let path = PathBuf::from(env.get_string(&name)?.to_str()?);
            paths.push(path);
        }
        let model = if paths.is_empty() { None } else {
            let config: serde_json::Value = serde_json::from_reader(std::fs::File::open(directory.join("config.json"))?)?;
            if context_tokens < 2 { bail!("Invalid model context"); }
            // Files remain immutable and leased by the Android runtime owner.
            let vb = unsafe { VarBuilder::from_mmaped_safetensors(&paths, DType::F32, &Device::Cpu)? };
            Some(cpu_pool().install(|| TextModel::load(config, vb, context_tokens as usize))?)
        };
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions().lock().map_err(|_| anyhow!("Session registry unavailable"))?.insert(id, Arc::new(Mutex::new(Session { tokenizer, model })));
        Ok(id)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_encode(mut env: JNIEnv, _: JClass, handle: jlong, text: JString) -> jintArray {
    guarded(&mut env, |env| {
        let text: String = env.get_string(&text)?.into();
        let shared = session(handle)?;
        let guard = shared.lock().map_err(|_| anyhow!("Session unavailable"))?;
        let encoding = guard.tokenizer.encode(text, true).map_err(|e| anyhow!(e.to_string()))?;
        let values: Vec<i32> = encoding.get_ids().iter().map(|&v| i32::try_from(v)).collect::<std::result::Result<_, _>>()?;
        let result = env.new_int_array(values.len() as i32)?;
        env.set_int_array_region(&result, 0, &values)?;
        Ok(result.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_encodeRendered(mut env: JNIEnv, _: JClass, handle: jlong, text: JString) -> jintArray {
    guarded(&mut env, |env| {
        let text: String = env.get_string(&text)?.into();
        let shared = session(handle)?;
        let guard = shared.lock().map_err(|_| anyhow!("Session unavailable"))?;
        let encoding = guard.tokenizer.encode(text, false).map_err(|e| anyhow!(e.to_string()))?;
        let values: Vec<i32> = encoding.get_ids().iter().map(|&v| i32::try_from(v)).collect::<std::result::Result<_, _>>()?;
        let result = env.new_int_array(values.len() as i32)?;
        env.set_int_array_region(&result, 0, &values)?;
        Ok(result.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_decode(mut env: JNIEnv, _: JClass, handle: jlong, tokens: JIntArray) -> jstring {
    guarded(&mut env, |env| {
        let values = ids(env, &tokens)?;
        let shared = session(handle)?;
        let guard = shared.lock().map_err(|_| anyhow!("Session unavailable"))?;
        let text = guard.tokenizer.decode(&values, true).map_err(|e| anyhow!(e.to_string()))?;
        Ok(env.new_string(text)?.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_forward(mut env: JNIEnv, _: JClass, handle: jlong, tokens: JIntArray, position: i32) -> jfloatArray {
    guarded(&mut env, |env| {
        let values = ids(env, &tokens)?;
        if values.is_empty() || position < 0 { bail!("Empty input or invalid position"); }
        let shared = session(handle)?;
        let mut guard = shared.lock().map_err(|_| anyhow!("Session unavailable"))?;
        let model = guard.model.as_mut().ok_or_else(|| anyhow!("Session has no Candle model"))?;
        let input = Tensor::new(values.as_slice(), &Device::Cpu)?.unsqueeze(0)?;
        let logits = cpu_pool().install(|| model.forward(&input, position as usize))?.flatten_all()?.to_vec1::<f32>()?;
        let result = env.new_float_array(logits.len() as i32)?;
        env.set_float_array_region(&result, 0, &logits)?;
        Ok(result.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_reset(mut env: JNIEnv, _: JClass, handle: jlong) {
    guarded(&mut env, |_| {
        let shared = session(handle)?;
        let mut guard = shared.lock().map_err(|_| anyhow!("Session unavailable"))?;
        if let Some(model) = guard.model.as_mut() { model.reset()?; }
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_close(mut env: JNIEnv, _: JClass, handle: jlong) {
    guarded(&mut env, |_| {
        sessions().lock().map_err(|_| anyhow!("Session registry unavailable"))?.remove(&handle);
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_tokenId(mut env: JNIEnv, _: JClass, handle: jlong, token: JString) -> i32 {
    guarded(&mut env, |env| {
        let name: String = env.get_string(&token)?.into();
        let shared = session(handle)?;
        let guard = shared.lock().map_err(|_| anyhow!("Tokenizer unavailable"))?;
        Ok(guard.tokenizer.token_to_id(&name).map(|v| v as i32).unwrap_or(-1))
    })
}
#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_ai_CandleBridge_languageTokens(mut env: JNIEnv, _: JClass, handle: jlong) -> jintArray {
    guarded(&mut env, |env| {
        let shared = session(handle)?;
        let guard = shared.lock().map_err(|_| anyhow!("Tokenizer unavailable"))?;
        let values: Vec<i32> = guard.tokenizer.get_vocab(true).into_iter().filter(|(name, _)| name.starts_with("<|") && name.ends_with("|>") &&
            (6..=7).contains(&name.len()) && name[2..name.len()-2].bytes().all(|c| c.is_ascii_lowercase())).map(|(_, id)| id as i32).collect();
        let result = env.new_int_array(values.len() as i32)?;
        env.set_int_array_region(&result, 0, &values)?;
        Ok(result.into_raw())
    })
}
