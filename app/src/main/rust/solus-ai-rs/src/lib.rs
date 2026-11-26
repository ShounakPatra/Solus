use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};
use jni::JNIEnv;
use serde_json::Value;
use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ============================================================================
// Wire Codec (matches rustanything.RustAnythingWire)
// ============================================================================

struct WireReader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> WireReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, pos: 0 }
    }

    fn tag(&mut self) -> Result<u8, String> {
        if self.pos >= self.bytes.len() {
            return Err("Unexpected EOF reading wire tag".into());
        }
        let b = self.bytes[self.pos];
        self.pos += 1;
        Ok(b)
    }

    fn len(&mut self) -> Result<usize, String> {
        if self.pos + 4 > self.bytes.len() {
            return Err("Unexpected EOF reading wire len".into());
        }
        let v = u32::from_le_bytes(self.bytes[self.pos..self.pos + 4].try_into().unwrap());
        self.pos += 4;
        Ok(v as usize)
    }

    fn i64(&mut self) -> Result<i64, String> {
        if self.pos + 8 > self.bytes.len() {
            return Err("Unexpected EOF reading wire i64".into());
        }
        let v = i64::from_le_bytes(self.bytes[self.pos..self.pos + 8].try_into().unwrap());
        self.pos += 8;
        Ok(v)
    }

    fn bool(&mut self) -> Result<bool, String> {
        Ok(self.tag()? != 0)
    }

    fn str(&mut self) -> Result<Option<String>, String> {
        if self.tag()? == 0 {
            return Ok(None);
        }
        let n = self.len()?;
        if self.pos + n > self.bytes.len() {
            return Err("Unexpected EOF reading wire string".into());
        }
        let s = std::str::from_utf8(&self.bytes[self.pos..self.pos + n])
            .map_err(|e| format!("Invalid UTF-8 in wire string: {e}"))?
            .to_owned();
        self.pos += n;
        Ok(Some(s))
    }

    fn ints_opt(&mut self) -> Result<Option<Vec<i32>>, String> {
        if self.tag()? == 0 {
            return Ok(None);
        }
        let n = self.len()?;
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.i64()? as i32);
        }
        Ok(Some(out))
    }

    fn string_list_opt(&mut self) -> Result<Option<Vec<Option<String>>>, String> {
        if self.tag()? == 0 {
            return Ok(None);
        }
        let n = self.len()?;
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.str()?);
        }
        Ok(Some(out))
    }
}

struct WireWriter {
    buf: Vec<u8>,
}

impl WireWriter {
    fn new() -> Self {
        Self { buf: Vec::with_capacity(64) }
    }

    fn tag(&mut self, t: u8) {
        self.buf.push(t);
    }

    fn len(&mut self, v: usize) {
        self.buf.extend_from_slice(&(v as u32).to_le_bytes());
    }

    fn i64(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    fn f64(&mut self, v: f64) {
        self.i64(v.to_bits() as i64);
    }

    fn str(&mut self, s: &str) {
        let bytes = s.as_bytes();
        self.tag(1);
        self.len(bytes.len());
        self.buf.extend_from_slice(bytes);
    }

    fn string_list(&mut self, items: &[String]) {
        self.tag(1);
        self.len(items.len());
        for s in items {
            self.str(s);
        }
    }

    fn ints_from_i32(&mut self, items: &[i32]) {
        self.tag(1);
        self.len(items.len());
        for &v in items {
            self.i64(v as i64);
        }
    }

    fn doubles(&mut self, items: &[f64]) {
        self.tag(1);
        self.len(items.len());
        for &v in items {
            self.f64(v);
        }
    }
}

// ============================================================================
// JNI Helpers
// ============================================================================

#[derive(Debug)]
pub struct RustErr {
    java_type: &'static str,
    message: String,
}

impl RustErr {
    fn illegal_arg(msg: impl Into<String>) -> Self {
        Self { java_type: "IllegalArgumentException", message: msg.into() }
    }
    fn runtime(msg: impl Into<String>) -> Self {
        Self { java_type: "RuntimeException", message: msg.into() }
    }
}

fn write_slot(env: &mut JNIEnv, slot: &JObjectArray, java_type: &str, message: &str) {
    if let Ok(t) = env.new_string(java_type) {
        let _ = env.set_object_array_element(slot, 0, t);
    }
    if let Ok(m) = env.new_string(message) {
        let _ = env.set_object_array_element(slot, 1, m);
    }
}

fn guarded_slot<T: Default>(
    env: &mut JNIEnv,
    slot: &JObjectArray,
    op: impl FnOnce(&mut JNIEnv) -> Result<T, RustErr>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| op(env))) {
        Ok(Ok(val)) => val,
        Ok(Err(err)) => {
            write_slot(env, slot, err.java_type, &err.message);
            T::default()
        }
        Err(_) => {
            write_slot(env, slot, "RuntimeException", "Rust panic in ai_rs");
            T::default()
        }
    }
}

fn get_jstring(env: &mut JNIEnv, s: &JString) -> Result<String, RustErr> {
    if s.is_null() {
        return Err(RustErr::illegal_arg("Null string argument"));
    }
    env.get_string(s)
        .map(|js| js.into())
        .map_err(|e| RustErr::runtime(format!("JNI string read error: {e}")))
}

fn new_jstring(env: &mut JNIEnv, s: &str) -> Result<jstring, RustErr> {
    env.new_string(s)
        .map(|js| js.into_raw())
        .map_err(|e| RustErr::runtime(format!("JNI string alloc error: {e}")))
}

// ============================================================================
// Core Logic (matches AiCoreLogic.java)
// ============================================================================

pub fn half_to_float(bits: i32) -> f64 {
    let sign = ((bits & 0x8000) as u32) << 16;
    let exponent = (bits >> 10) & 31;
    let mantissa = (bits & 1023) as u32;
    let f = if exponent == 0 {
        let s = if (bits & 0x8000) != 0 { -1.0f32 } else { 1.0f32 };
        s * (mantissa as f32) * (1.0f32 / 16777216.0f32)
    } else if exponent == 31 {
        f32::from_bits(sign | 0x7f800000 | (mantissa << 13))
    } else {
        f32::from_bits(sign | (((exponent + 112) as u32) << 23) | (mantissa << 13))
    };
    f as f64
}

pub fn bfloat16_to_float(bits: i32) -> f64 {
    f32::from_bits(((bits & 0xffff) as u32) << 16) as f64
}

pub fn convert_shorts_to_doubles(shorts: &[i32], is_bfloat16: bool) -> Vec<f64> {
    shorts
        .iter()
        .map(|&s| {
            let bits = s & 0xffff;
            if is_bfloat16 { bfloat16_to_float(bits) } else { half_to_float(bits) }
        })
        .collect()
}

pub fn sanitize_extension(name: &str) -> String {
    let ext = match name.rfind('.') {
        Some(dot) => &name[dot + 1..],
        None => "bin",
    };
    ext.chars().filter(|c| c.is_alphanumeric()).take(12).collect()
}

pub fn file_identity(path: &str, length: i64, last_modified: i64) -> String {
    format!("{path}|{length}|{last_modified}")
}

pub fn stop_sequences(format_or_template: &str) -> Vec<String> {
    let mut list = Vec::new();
    if format_or_template == "chatml" || format_or_template.contains("<|im_end|>") {
        list.push("<|im_end|>".to_owned());
    }
    if format_or_template == "llama3" || format_or_template.contains("<|eot_id|>") {
        list.push("<|eot_id|>".to_owned());
    }
    if format_or_template == "gemma" || format_or_template.contains("<end_of_turn>") {
        list.push("<end_of_turn>".to_owned());
    }
    if format_or_template == "phi3" || format_or_template.contains("<|end|>") {
        list.push("<|end|>".to_owned());
    }
    list
}

pub fn detect_fallback_format(model_type: &str, solus_format: &str, special_tokens: &[String]) -> String {
    if !solus_format.trim().is_empty() {
        return solus_format.to_owned();
    }
    let token_set: HashSet<&str> = special_tokens.iter().map(String::as_str).collect();
    if token_set.contains("<|im_start|>") && token_set.contains("<|im_end|>") {
        return "chatml".to_owned();
    }
    if token_set.contains("<|start_header_id|>")
        && token_set.contains("<|end_header_id|>")
        && token_set.contains("<|eot_id|>")
    {
        return "llama3".to_owned();
    }
    match model_type {
        "qwen2" => "chatml",
        "gpt2" => "completion",
        "llama" => "llama2",
        "mistral" => "mistral",
        "gemma" => "gemma",
        "phi3" => "phi3",
        "phi" => "phi",
        _ => "plain",
    }
    .to_owned()
}

pub fn render_prompt(format: &str, roles: &[String], texts: &[String]) -> Result<String, RustErr> {
    let n = roles.len().min(texts.len());
    for i in 0..n {
        let r = roles[i].as_str();
        if r != "system" && r != "user" && r != "assistant" {
            return Err(RustErr::illegal_arg(format!("Invalid role: {r}")));
        }
    }
    match format {
        "completion" => Ok(texts[..n].join("\n\n")),
        "plain" => {
            let body: Vec<String> = (0..n).map(|i| format!("{}: {}", roles[i], texts[i])).collect();
            Ok(format!("{}\n\nassistant:", body.join("\n\n")))
        }
        "chatml" => {
            let mut sb = String::new();
            for i in 0..n {
                sb.push_str("<|im_start|>");
                sb.push_str(&roles[i]);
                sb.push('\n');
                sb.push_str(&texts[i]);
                sb.push_str("<|im_end|>\n");
            }
            sb.push_str("<|im_start|>assistant\n");
            Ok(sb)
        }
        "llama3" => {
            let mut sb = String::from("<|begin_of_text|>");
            for i in 0..n {
                sb.push_str("<|start_header_id|>");
                sb.push_str(&roles[i]);
                sb.push_str("<|end_header_id|>\n\n");
                sb.push_str(&texts[i]);
                sb.push_str("<|eot_id|>");
            }
            sb.push_str("<|start_header_id|>assistant<|end_header_id|>\n\n");
            Ok(sb)
        }
        "phi3" => {
            let mut sb = String::new();
            for i in 0..n {
                sb.push_str("<|");
                sb.push_str(&roles[i]);
                sb.push_str("|>\n");
                sb.push_str(&texts[i]);
                sb.push_str("<|end|>\n");
            }
            sb.push_str("<|assistant|>\n");
            Ok(sb)
        }
        "gemma" => {
            let system = (0..n)
                .filter(|&i| roles[i] == "system")
                .map(|i| texts[i].as_str())
                .collect::<Vec<_>>()
                .join("\n\n");
            let mut sb = String::from("<bos>");
            let mut first = true;
            for i in 0..n {
                let role = roles[i].as_str();
                if role == "system" {
                    continue;
                }
                let text = &texts[i];
                let mapped_role = if role == "assistant" { "model" } else { role };
                sb.push_str("<start_of_turn>");
                sb.push_str(mapped_role);
                sb.push('\n');
                if first && role == "user" && !system.trim().is_empty() {
                    sb.push_str(&system);
                    sb.push_str("\n\n");
                }
                sb.push_str(text);
                sb.push_str("<end_of_turn>\n");
                first = false;
            }
            sb.push_str("<start_of_turn>model\n");
            Ok(sb)
        }
        "llama2" | "mistral" => {
            let system = (0..n)
                .filter(|&i| roles[i] == "system")
                .map(|i| texts[i].as_str())
                .collect::<Vec<_>>()
                .join("\n\n");
            let mut sb = String::new();
            let mut first = true;
            for i in 0..n {
                let role = roles[i].as_str();
                if role == "system" {
                    continue;
                }
                let text = &texts[i];
                if role == "user" {
                    if first || format == "llama2" {
                        sb.push_str("<s>");
                    }
                    sb.push_str("[INST] ");
                    if first && !system.trim().is_empty() {
                        if format == "llama2" {
                            sb.push_str("<<SYS>>\n");
                            sb.push_str(&system);
                            sb.push_str("\n<</SYS>>\n\n");
                        } else {
                            sb.push_str(&system);
                            sb.push_str("\n\n");
                        }
                    }
                    sb.push_str(text);
                    sb.push_str(" [/INST]");
                    first = false;
                } else {
                    sb.push(' ');
                    sb.push_str(text);
                    sb.push_str("</s>");
                }
            }
            Ok(sb)
        }
        "phi" => {
            let body: Vec<String> = (0..n)
                .map(|i| {
                    let prefix = if roles[i] == "assistant" { "Output" } else { "Instruct" };
                    format!("{prefix}: {}", texts[i])
                })
                .collect();
            Ok(format!("{}\nOutput:", body.join("\n")))
        }
        other => Err(RustErr::illegal_arg(format!("Unsupported prompt format {other}"))),
    }
}

pub fn drop_oldest_turn_indices(roles: &[String]) -> Vec<i32> {
    let users: Vec<usize> = roles
        .iter()
        .enumerate()
        .filter(|(_, r)| r.as_str() == "user")
        .map(|(i, _)| i)
        .collect();
    if users.len() < 2 {
        return Vec::new();
    }
    let drop_start = users[0];
    let drop_end = users[1];
    roles
        .iter()
        .enumerate()
        .filter(|&(i, r)| r == "system" || i < drop_start || i >= drop_end)
        .map(|(i, _)| i as i32)
        .collect()
}

pub fn effective_answer(raw: &str, fallback: &str) -> String {
    let t = raw.trim();
    if !t.is_empty() { t.to_owned() } else { fallback.trim().to_owned() }
}

pub fn effective_reasoning(raw: &str, fallback: &str) -> String {
    let t = raw.trim();
    if !t.is_empty() { t.to_owned() } else { fallback.trim().to_owned() }
}

pub fn finalize_status(
    effective_answer: &str,
    effective_reasoning: &str,
    is_stopped: bool,
    has_error: bool,
) -> i32 {
    if is_stopped {
        return 1;
    }
    if has_error {
        return 2;
    }
    if !effective_answer.trim().is_empty() {
        return 0;
    }
    if !effective_reasoning.trim().is_empty() {
        return 3;
    }
    4
}

// ============================================================================
// JNI Exports: AiRsNative
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1halfToFloat(
    mut env: JNIEnv,
    _: JClass,
    bits: jint,
    slot: JObjectArray,
) -> jdouble {
    guarded_slot(&mut env, &slot, |_| Ok(half_to_float(bits)))
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1bfloat16ToFloat(
    mut env: JNIEnv,
    _: JClass,
    bits: jint,
    slot: JObjectArray,
) -> jdouble {
    guarded_slot(&mut env, &slot, |_| Ok(bfloat16_to_float(bits)))
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1sanitizeExtension(
    mut env: JNIEnv,
    _: JClass,
    name: JString,
    slot: JObjectArray,
) -> jstring {
    guarded_slot(&mut env, &slot, |env| {
        let n = get_jstring(env, &name)?;
        new_jstring(env, &sanitize_extension(&n))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1fileIdentity(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
    length: jlong,
    last_modified: jlong,
    slot: JObjectArray,
) -> jstring {
    guarded_slot(&mut env, &slot, |env| {
        let p = get_jstring(env, &path)?;
        new_jstring(env, &file_identity(&p, length, last_modified))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1effectiveAnswer(
    mut env: JNIEnv,
    _: JClass,
    raw: JString,
    fallback: JString,
    slot: JObjectArray,
) -> jstring {
    guarded_slot(&mut env, &slot, |env| {
        let r = get_jstring(env, &raw)?;
        let f = get_jstring(env, &fallback)?;
        new_jstring(env, &effective_answer(&r, &f))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1effectiveReasoning(
    mut env: JNIEnv,
    _: JClass,
    raw: JString,
    fallback: JString,
    slot: JObjectArray,
) -> jstring {
    guarded_slot(&mut env, &slot, |env| {
        let r = get_jstring(env, &raw)?;
        let f = get_jstring(env, &fallback)?;
        new_jstring(env, &effective_reasoning(&r, &f))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_n_1finalizeStatus(
    mut env: JNIEnv,
    _: JClass,
    answer: JString,
    reasoning: JString,
    is_stopped: jboolean,
    has_error: jboolean,
    slot: JObjectArray,
) -> jint {
    guarded_slot(&mut env, &slot, |env| {
        let a = get_jstring(env, &answer)?;
        let r = get_jstring(env, &reasoning)?;
        Ok(finalize_status(&a, &r, is_stopped != 0, has_error != 0))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_w_1convertShortsToDoubles(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = env
            .convert_byte_array(&args)
            .map_err(|e| RustErr::runtime(format!("Failed to read byte array: {e}")))?;
        let mut r = WireReader::new(&bytes);
        let shorts = r.ints_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let is_bf16 = r.bool().map_err(RustErr::illegal_arg)?;
        let doubles = convert_shorts_to_doubles(&shorts, is_bf16);
        let mut w = WireWriter::new();
        w.doubles(&doubles);
        env.byte_array_from_slice(&w.buf)
            .map(|a| a.into_raw())
            .map_err(|e| RustErr::runtime(format!("Failed to alloc byte array: {e}")))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_w_1stopSequences(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = env
            .convert_byte_array(&args)
            .map_err(|e| RustErr::runtime(format!("Failed to read byte array: {e}")))?;
        let mut r = WireReader::new(&bytes);
        let fmt = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let seqs = stop_sequences(&fmt);
        let mut w = WireWriter::new();
        w.string_list(&seqs);
        env.byte_array_from_slice(&w.buf)
            .map(|a| a.into_raw())
            .map_err(|e| RustErr::runtime(format!("Failed to alloc byte array: {e}")))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_w_1detectFallbackFormat(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = env
            .convert_byte_array(&args)
            .map_err(|e| RustErr::runtime(format!("Failed to read byte array: {e}")))?;
        let mut r = WireReader::new(&bytes);
        let model_type = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let solus_format = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let special_tokens: Vec<String> = r
            .string_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or_default())
            .collect();
        let fmt = detect_fallback_format(&model_type, &solus_format, &special_tokens);
        let mut w = WireWriter::new();
        w.str(&fmt);
        env.byte_array_from_slice(&w.buf)
            .map(|a| a.into_raw())
            .map_err(|e| RustErr::runtime(format!("Failed to alloc byte array: {e}")))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_w_1renderPrompt(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = env
            .convert_byte_array(&args)
            .map_err(|e| RustErr::runtime(format!("Failed to read byte array: {e}")))?;
        let mut r = WireReader::new(&bytes);
        let fmt = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let roles: Vec<String> = r
            .string_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or_default())
            .collect();
        let texts: Vec<String> = r
            .string_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or_default())
            .collect();
        let rendered = render_prompt(&fmt, &roles, &texts)?;
        let mut w = WireWriter::new();
        w.str(&rendered);
        env.byte_array_from_slice(&w.buf)
            .map(|a| a.into_raw())
            .map_err(|e| RustErr::runtime(format!("Failed to alloc byte array: {e}")))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_w_1dropOldestTurnIndices(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = env
            .convert_byte_array(&args)
            .map_err(|e| RustErr::runtime(format!("Failed to read byte array: {e}")))?;
        let mut r = WireReader::new(&bytes);
        let roles: Vec<String> = r
            .string_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or_default())
            .collect();
        let indices = drop_oldest_turn_indices(&roles);
        let mut w = WireWriter::new();
        w.ints_from_i32(&indices);
        env.byte_array_from_slice(&w.buf)
            .map(|a| a.into_raw())
            .map_err(|e| RustErr::runtime(format!("Failed to alloc byte array: {e}")))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_AiRsNative_call(
    mut env: JNIEnv,
    _: JClass,
    name: JString,
    args: JString,
) -> jstring {
    let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Value, String> {
        let fn_name = get_jstring(&mut env, &name).map_err(|e| e.message)?;
        let args_str = get_jstring(&mut env, &args).map_err(|e| e.message)?;
        let parsed: Value = serde_json::from_str(&args_str).map_err(|e| e.to_string())?;
        let arr = parsed.as_array().ok_or_else(|| "Expected JSON array args".to_owned())?;
        match fn_name.as_str() {
            "sanitizeExtension" => {
                let s = arr.first().and_then(|v| v.as_str()).unwrap_or_default();
                Ok(serde_json::json!({ "ok": sanitize_extension(s) }))
            }
            "finalizeStatus" => {
                let a = arr.first().and_then(|v| v.as_str()).unwrap_or_default();
                let r = arr.get(1).and_then(|v| v.as_str()).unwrap_or_default();
                let stopped = arr.get(2).and_then(|v| v.as_bool()).unwrap_or(false);
                let err = arr.get(3).and_then(|v| v.as_bool()).unwrap_or(false);
                Ok(serde_json::json!({ "ok": finalize_status(a, r, stopped, err) }))
            }
            other => Err(format!("UnsupportedOperationException: Unknown AiRsNative call '{other}'")),
        }
    }));

    let payload = match outcome {
        Ok(Ok(v)) => v.to_string(),
        Ok(Err(err)) => serde_json::json!({ "error": err }).to_string(),
        Err(_) => serde_json::json!({ "panic": "Rust panic in AiRsNative.call" }).to_string(),
    };
    env.new_string(payload)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fp16_and_bf16_conversions() {
        // 0x3c00 in FP16 = 1.0, 0x3f80 in BF16 = 1.0
        assert!((half_to_float(0x3c00) - 1.0).abs() < 1e-6);
        assert!((bfloat16_to_float(0x3f80) - 1.0).abs() < 1e-6);
        assert_eq!(convert_shorts_to_doubles(&[0x3c00], false), vec![1.0]);
        assert_eq!(convert_shorts_to_doubles(&[0x3f80], true), vec![1.0]);
    }

    #[test]
    fn prompt_rendering_and_history_drop() {
        let roles = vec!["system".into(), "user".into(), "assistant".into(), "user".into()];
        let texts = vec!["sys".into(), "hi".into(), "hello".into(), "bye".into()];
        let rendered = render_prompt("chatml", &roles, &texts).unwrap();
        assert!(rendered.starts_with("<|im_start|>system\nsys<|im_end|>"));
        assert!(rendered.ends_with("<|im_start|>assistant\n"));
        assert_eq!(drop_oldest_turn_indices(&roles), vec![0, 3]);
    }
}
