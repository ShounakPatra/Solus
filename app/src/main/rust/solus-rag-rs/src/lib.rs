use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jintArray, jlong, jstring, jbooleanArray, jdoubleArray};
use jni::JNIEnv;
use rayon::prelude::*;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, OnceLock};

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

    fn f64(&mut self) -> Result<f64, String> {
        Ok(f64::from_bits(self.i64()? as u64))
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

    fn doubles_opt(&mut self) -> Result<Option<Vec<f64>>, String> {
        if self.tag()? == 0 {
            return Ok(None);
        }
        let n = self.len()?;
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            out.push(self.f64()?);
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

    fn opt_bool_list_opt(&mut self) -> Result<Option<Vec<Option<bool>>>, String> {
        if self.tag()? == 0 {
            return Ok(None);
        }
        let n = self.len()?;
        let mut out = Vec::with_capacity(n);
        for _ in 0..n {
            if self.tag()? == 0 {
                out.push(None);
            } else {
                out.push(Some(self.bool()?));
            }
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

    fn bool(&mut self, v: bool) {
        self.tag(if v { 1 } else { 0 });
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
// JNI Helpers & Error Slot Handling
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
    fn illegal_state(msg: impl Into<String>) -> Self {
        Self { java_type: "IllegalStateException", message: msg.into() }
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
            write_slot(env, slot, "RuntimeException", "Rust panic in rag_rs");
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

fn get_jstring_opt(env: &mut JNIEnv, s: &JString) -> Result<Option<String>, RustErr> {
    if s.is_null() {
        return Ok(None);
    }
    env.get_string(s)
        .map(|js| Some(js.into()))
        .map_err(|e| RustErr::runtime(format!("JNI string read error: {e}")))
}

fn bytes_to_jbytearray(env: &mut JNIEnv, data: &[u8]) -> Result<jbyteArray, RustErr> {
    let arr = env
        .byte_array_from_slice(data)
        .map_err(|e| RustErr::runtime(format!("Failed to allocate byte array: {e}")))?;
    Ok(arr.into_raw())
}

fn jbytearray_to_vec(env: &JNIEnv, arr: &JByteArray) -> Result<Vec<u8>, RustErr> {
    env.convert_byte_array(arr)
        .map_err(|e| RustErr::runtime(format!("Failed to read byte array: {e}")))
}

// ============================================================================
// 1. Text Chunking (UTF-16 code-unit compatible with RagCoreLogic.kt)
// ============================================================================

fn last_index_of_seq(haystack: &[u16], needle: &[u16]) -> Option<usize> {
    if needle.is_empty() || needle.len() > haystack.len() {
        return None;
    }
    haystack
        .windows(needle.len())
        .rposition(|w| w == needle)
}

pub fn find_break_point_utf16(units: &[u16], start: usize, end: usize, chunk_size: usize) -> usize {
    if chunk_size == 0 || start >= end || end > units.len() {
        return end;
    }
    let back = ((chunk_size as f32) * 0.35f32) as usize;
    let window_start = end.saturating_sub(back).max(start + 10);
    if window_start >= end {
        return end;
    }
    let slice = &units[window_start..end];

    // 1. Paragraph break "\n\n"
    if let Some(idx) = last_index_of_seq(slice, &[b'\n' as u16, b'\n' as u16]) {
        return window_start + idx + 2;
    }
    // 2. Line break '\n'
    if let Some(idx) = slice.iter().rposition(|&c| c == b'\n' as u16) {
        return window_start + idx + 1;
    }
    // 3. Sentence break ". ", "? ", "! "
    for pat in [&[b'.' as u16, b' ' as u16], &[b'?' as u16, b' ' as u16], &[b'!' as u16, b' ' as u16]] {
        if let Some(idx) = last_index_of_seq(slice, pat) {
            return window_start + idx + pat.len();
        }
    }
    // 4. Clause break "; ", ": ", ", "
    for pat in [&[b';' as u16, b' ' as u16], &[b':' as u16, b' ' as u16], &[b',' as u16, b' ' as u16]] {
        if let Some(idx) = last_index_of_seq(slice, pat) {
            return window_start + idx + pat.len();
        }
    }
    // 5. Word boundary ' '
    if let Some(idx) = slice.iter().rposition(|&c| c == b' ' as u16) {
        return window_start + idx + 1;
    }
    end
}

pub fn find_break_point(text: &str, start: i32, end: i32, chunk_size: i32) -> i32 {
    if chunk_size <= 0 || start >= end || start < 0 {
        return end;
    }
    let units: Vec<u16> = text.encode_utf16().collect();
    let e = (end as usize).min(units.len());
    let s = (start as usize).min(e);
    find_break_point_utf16(&units, s, e, chunk_size as usize) as i32
}

fn trim_utf16_slice(units: &[u16]) -> String {
    let decoded = String::from_utf16_lossy(units);
    decoded.trim().to_owned()
}

pub fn chunk_text(document_text: &str, chunk_size: i32, chunk_overlap: i32) -> Result<Vec<String>, RustErr> {
    if chunk_size <= 0 {
        return Err(RustErr::illegal_arg("chunkSize must be positive"));
    }
    if chunk_overlap < 0 || chunk_overlap >= chunk_size {
        return Err(RustErr::illegal_arg("chunkOverlap must be non-negative and smaller than chunkSize"));
    }
    let trimmed = document_text.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }
    let units: Vec<u16> = trimmed.encode_utf16().collect();
    let cs = chunk_size as usize;
    let ov = chunk_overlap as usize;
    if units.len() <= cs {
        return Ok(vec![trimmed.to_owned()]);
    }

    let mut chunks = Vec::new();
    let mut start = 0usize;
    while start < units.len() {
        let potential_end = (start + cs).min(units.len());
        if potential_end == units.len() {
            let chunk = trim_utf16_slice(&units[start..potential_end]);
            if !chunk.is_empty() {
                chunks.push(chunk);
            }
            break;
        }
        let break_point = find_break_point_utf16(&units, start, potential_end, cs);
        let chunk = trim_utf16_slice(&units[start..break_point]);
        if !chunk.is_empty() {
            chunks.push(chunk);
        }
        if break_point <= start {
            start = potential_end;
        } else {
            let next_start = break_point.saturating_sub(ov);
            start = if next_start > start { next_start } else { break_point };
        }
        if start >= units.len() {
            break;
        }
    }
    Ok(chunks)
}

pub fn chunk_boundaries(document_text: &str, chunk_size: i32, chunk_overlap: i32) -> Result<Vec<i32>, RustErr> {
    if chunk_size <= 0 {
        return Err(RustErr::illegal_arg("chunkSize must be positive"));
    }
    if chunk_overlap < 0 || chunk_overlap >= chunk_size {
        return Err(RustErr::illegal_arg("chunkOverlap must be non-negative and smaller than chunkSize"));
    }
    let trimmed = document_text.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }
    let units: Vec<u16> = trimmed.encode_utf16().collect();
    let cs = chunk_size as usize;
    let ov = chunk_overlap as usize;
    if units.len() <= cs {
        return Ok(vec![0, units.len() as i32]);
    }

    let mut list = Vec::new();
    let mut start = 0usize;
    while start < units.len() {
        let potential_end = (start + cs).min(units.len());
        if potential_end == units.len() {
            let chunk = trim_utf16_slice(&units[start..potential_end]);
            if !chunk.is_empty() {
                list.push(start as i32);
                list.push(potential_end as i32);
            }
            break;
        }
        let break_point = find_break_point_utf16(&units, start, potential_end, cs);
        let chunk = trim_utf16_slice(&units[start..break_point]);
        if !chunk.is_empty() {
            list.push(start as i32);
            list.push(break_point as i32);
        }
        if break_point <= start {
            start = potential_end;
        } else {
            let next_start = break_point.saturating_sub(ov);
            start = if next_start > start { next_start } else { break_point };
        }
        if start >= units.len() {
            break;
        }
    }
    Ok(list)
}

// ============================================================================
// 2. Vector Embedding Math
// ============================================================================

pub fn dot_product(a: &[f64], b: &[f64]) -> f64 {
    a.iter().zip(b.iter()).map(|(x, y)| x * y).sum()
}

pub fn magnitude(v: &[f64]) -> f64 {
    v.iter().map(|x| x * x).sum::<f64>().sqrt()
}

pub fn l2_normalize(v: &[f64]) -> Vec<f64> {
    let mag = magnitude(v);
    if mag <= 1e-9 {
        return vec![0.0; v.len()];
    }
    let inv = 1.0 / mag;
    v.iter().map(|x| x * inv).collect()
}

pub fn cosine_similarity(a: &[f64], b: &[f64]) -> f64 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let dot = dot_product(a, b);
    let denom = magnitude(a) * magnitude(b);
    if denom <= 1e-9 {
        return 0.0;
    }
    (dot / denom).clamp(-1.0, 1.0)
}

// ============================================================================
// 3. Universal Semantic Embedding
// ============================================================================

const SEED_1: i64 = -3750763034362895579;
const SEED_2: i64 = 0x100000001b3;
const SEED_3: i64 = -7046029254386353131;
const FNV_PRIME: u64 = 0x100000001b3;

pub fn fnv1a64(text: &str, seed: i64) -> i64 {
    let mut hash = seed as u64;
    for unit in text.encode_utf16() {
        hash ^= unit as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash as i64
}

fn is_embed_stop_word(token: &str) -> bool {
    matches!(
        token,
        "a" | "about" | "above" | "after" | "again" | "against" | "all" | "am" | "an" | "and"
            | "any" | "are" | "aren't" | "as" | "at" | "be" | "because" | "been" | "before" | "being"
            | "below" | "between" | "both" | "but" | "by" | "can" | "can't" | "cannot" | "could"
            | "couldn't" | "did" | "didn't" | "do" | "does" | "doesn't" | "doing" | "don't" | "down"
            | "during" | "each" | "few" | "for" | "from" | "further" | "had" | "hadn't" | "has"
            | "hasn't" | "have" | "haven't" | "having" | "he" | "he'd" | "he'll" | "he's" | "her"
            | "here" | "here's" | "hers" | "herself" | "him" | "himself" | "his" | "how" | "how's"
            | "i" | "i'd" | "i'll" | "i'm" | "i've" | "if" | "in" | "into" | "is" | "isn't" | "it"
            | "it's" | "its" | "itself" | "let's" | "me" | "more" | "most" | "mustn't" | "my"
            | "myself" | "no" | "nor" | "not" | "of" | "off" | "on" | "once" | "only" | "or" | "other"
            | "ought" | "our" | "ours" | "ourselves" | "out" | "over" | "own" | "same" | "shan't"
            | "she" | "she'd" | "she'll" | "she's" | "should" | "shouldn't" | "so" | "some" | "such"
            | "than" | "that" | "that's" | "the" | "their" | "theirs" | "them" | "themselves"
            | "then" | "there" | "there's" | "these" | "they" | "they'd" | "they'll" | "they're"
            | "they've" | "this" | "those" | "through" | "to" | "too" | "under" | "until" | "up"
            | "very" | "was" | "wasn't" | "we" | "we'd" | "we'll" | "we're" | "we've" | "were"
            | "weren't" | "what" | "what's" | "when" | "when's" | "where" | "where's" | "which"
            | "while" | "who" | "who's" | "whom" | "why" | "why's" | "with" | "won't" | "would"
            | "wouldn't" | "you" | "you'd" | "you'll" | "you're" | "you've" | "your" | "yours"
            | "yourself" | "yourselves"
    )
}

fn compute_token_weight(token: &str) -> f64 {
    if is_embed_stop_word(token) {
        return 0.18;
    }
    if token.chars().all(|c| c.is_ascii_digit()) || token.chars().any(|c| c.is_numeric()) {
        return 2.2;
    }
    let char_len = token.chars().count();
    if char_len <= 2 {
        return 0.4;
    }
    if char_len >= 8 {
        return 1.4;
    }
    1.0
}

fn project_token(token: &str, weight: f64, target: &mut [f64], dimension: usize) {
    let dim = dimension as u64;
    let h1 = fnv1a64(token, SEED_1) as u64;
    let h2 = fnv1a64(token, SEED_2) as u64;
    let h3 = fnv1a64(token, SEED_3) as u64;

    let idx1 = ((h1 >> 1) % dim) as usize;
    let sign1 = if (h1 & 1) == 0 { 1.0 } else { -1.0 };
    target[idx1] += sign1 * weight;

    let idx2 = ((h2 >> 1) % dim) as usize;
    let sign2 = if (h2 & 1) == 0 { 1.0 } else { -1.0 };
    target[idx2] += sign2 * (weight * 0.75);

    let idx3 = ((h3 >> 1) % dim) as usize;
    let sign3 = if (h3 & 1) == 0 { 1.0 } else { -1.0 };
    target[idx3] += sign3 * (weight * 0.5);
}

pub fn embed_universal(text: &str, dimension: i32) -> Vec<f64> {
    if dimension <= 0 {
        return Vec::new();
    }
    let dim = dimension as usize;
    if text.trim().is_empty() {
        return vec![0.0; dim];
    }

    let normalized = text.to_lowercase();
    let trimmed = normalized.trim();
    let mut raw_tokens: Vec<String> = Vec::new();
    let mut current = String::new();
    for c in trimmed.chars() {
        if c.is_alphanumeric() {
            current.push(c);
        } else if !current.is_empty() {
            raw_tokens.push(std::mem::take(&mut current));
        }
    }
    if !current.is_empty() {
        raw_tokens.push(current);
    }
    if raw_tokens.is_empty() {
        return vec![0.0; dim];
    }

    let mut vector = vec![0.0; dim];
    for (i, token) in raw_tokens.iter().enumerate() {
        let idf = compute_token_weight(token);
        let pos = 1.0 + 0.3 * (-(i as f64) / 15.0).exp();
        let total = idf * pos;

        project_token(token, total, &mut vector, dim);

        let subword_weight = total * 0.4;
        let bounded: Vec<char> = format!("<{token}>").chars().collect();
        let max_len = bounded.len().min(5);
        for n in 3..=max_len {
            for start in 0..=(bounded.len() - n) {
                let gram: String = bounded[start..start + n].iter().collect();
                project_token(&gram, subword_weight, &mut vector, dim);
            }
        }
    }

    for i in 0..raw_tokens.len().saturating_sub(1) {
        let bigram = format!("{}_{}", raw_tokens[i], raw_tokens[i + 1]);
        let w = (compute_token_weight(&raw_tokens[i]) + compute_token_weight(&raw_tokens[i + 1])) * 0.65;
        project_token(&bigram, w, &mut vector, dim);
    }

    l2_normalize(&vector)
}

// ============================================================================
// 4. Retrieval Relevance & Batch Processing
// ============================================================================

fn extract_words(text: &str) -> Vec<String> {
    let lower = text.to_lowercase();
    let mut words = Vec::new();
    let mut cur = String::new();
    for c in lower.chars() {
        if c.is_alphanumeric() {
            cur.push(c);
        } else if !cur.is_empty() {
            words.push(std::mem::take(&mut cur));
        }
    }
    if !cur.is_empty() {
        words.push(cur);
    }
    words
}

fn is_relevance_stop_word(word: &str) -> bool {
    matches!(
        word,
        "the" | "and" | "for" | "with" | "this" | "that" | "what" | "which" | "when" | "where"
            | "who" | "why" | "how" | "are" | "was" | "were" | "does" | "did" | "can" | "could"
            | "would" | "should" | "will" | "you" | "your" | "please" | "tell" | "about" | "have"
            | "has" | "had" | "from" | "into" | "under" | "over" | "is" | "it" | "its" | "of"
            | "to" | "in" | "on" | "at" | "an" | "as" | "be" | "by" | "do" | "me" | "my" | "we"
            | "us"
    )
}

pub fn is_conversation_only(query: &str) -> bool {
    let joined = extract_words(query).join(" ");
    matches!(
        joined.as_str(),
        "hi" | "hello" | "hey" | "hi there" | "hello there" | "hey there" | "good morning"
            | "good evening" | "good afternoon" | "thanks" | "thank you" | "thanks a lot" | "ok"
            | "okay" | "who are you" | "what are you" | "what is your name" | "what s your name"
            | "how are you" | "what can you do" | "tell me about yourself" | "what is my name"
            | "what s my name" | "tell me my name" | "who am i"
    )
}

pub fn is_unqualified_overview(query: &str) -> bool {
    let joined = extract_words(query).join(" ");
    matches!(
        joined.as_str(),
        "summarize" | "summarise" | "summary" | "overview" | "tldr" | "tl dr" | "outline"
            | "give me a summary" | "give me an overview" | "show key points" | "key points"
            | "main points"
    )
}

fn significant_query_words(query: &str) -> Vec<String> {
    extract_words(query)
        .into_iter()
        .filter(|w| w.chars().count() > 1 && !is_relevance_stop_word(w))
        .collect()
}

pub fn has_lexical_evidence(query: &str, content: &str) -> bool {
    let terms: HashSet<String> = significant_query_words(query).into_iter().collect();
    if terms.is_empty() {
        return false;
    }
    extract_words(content).iter().any(|w| terms.contains(w))
}

pub fn score_relevance(query: &str, content: &str) -> f64 {
    if is_conversation_only(query) {
        return 0.0;
    }
    let query_words = significant_query_words(query);
    if query_words.is_empty() {
        return 0.0;
    }
    let content_words: HashSet<String> = extract_words(content).into_iter().collect();
    let matches = query_words.iter().filter(|w| content_words.contains(w.as_str())).count();
    (matches as f64) / (query_words.len() as f64)
}

struct BatchIndex {
    docs: Vec<HashSet<String>>,
}

impl BatchIndex {
    fn from_items(items: Vec<String>) -> Self {
        let docs = items
            .into_par_iter()
            .map(|text| extract_words(&text).into_iter().collect())
            .collect();
        Self { docs }
    }

    fn has_lexical_evidence_all(&self, query: &str) -> Vec<bool> {
        let terms: HashSet<String> = significant_query_words(query).into_iter().collect();
        if terms.is_empty() {
            return vec![false; self.docs.len()];
        }
        self.docs
            .par_iter()
            .map(|doc| terms.iter().any(|t| doc.contains(t)))
            .collect()
    }

    fn score_all(&self, query: &str) -> Vec<f64> {
        if is_conversation_only(query) {
            return vec![0.0; self.docs.len()];
        }
        let query_words = significant_query_words(query);
        if query_words.is_empty() {
            return vec![0.0; self.docs.len()];
        }
        let denom = query_words.len() as f64;
        self.docs
            .par_iter()
            .map(|doc| {
                let mut total = 0.0f64;
                for q in &query_words {
                    if doc.contains(q) {
                        total += 1.0;
                    } else if q.len() >= 3 {
                        let mut best_prefix = 0.0f64;
                        for w in doc {
                            if w.starts_with(q.as_str()) {
                                best_prefix = best_prefix.max((q.len() as f64) / (w.len() as f64));
                            } else if q.starts_with(w.as_str()) && w.len() >= 3 {
                                best_prefix = best_prefix.max((w.len() as f64) / (q.len() as f64));
                            }
                        }
                        total += best_prefix;
                    }
                }
                total / denom
            })
            .collect()
    }
}

static BATCH_STORE: OnceLock<Mutex<HashMap<i64, BatchIndex>>> = OnceLock::new();
static NEXT_BATCH_HANDLE: AtomicI64 = AtomicI64::new(1);

fn batch_store() -> &'static Mutex<HashMap<i64, BatchIndex>> {
    BATCH_STORE.get_or_init(Default::default)
}

fn read_string_array(env: &mut JNIEnv, items: &JObjectArray) -> Result<Vec<String>, RustErr> {
    if items.is_null() {
        return Err(RustErr::illegal_arg("Null items array"));
    }
    let len = env
        .get_array_length(items)
        .map_err(|e| RustErr::runtime(format!("Array len error: {e}")))?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let obj = env
            .get_object_array_element(items, i)
            .map_err(|e| RustErr::runtime(format!("Array element error: {e}")))?;
        if obj.is_null() {
            out.push(String::new());
        } else {
            let js = JString::from(obj);
            let s: String = env
                .get_string(&js)
                .map(|v| v.into())
                .map_err(|e| RustErr::runtime(format!("Array string error: {e}")))?;
            out.push(s);
        }
    }
    Ok(out)
}

// ============================================================================
// 5. Turn Document Planning Helpers
// ============================================================================

pub fn plan_saved_upload_id(
    entry_ids: &[String],
    is_user_list: &[bool],
    paths: &[String],
    turn_message_id: Option<&str>,
) -> Option<String> {
    let n = entry_ids.len().min(is_user_list.len()).min(paths.len());
    let turn_index = turn_message_id.and_then(|id| (0..n).find(|&i| entry_ids[i] == id));
    let limit = turn_index.unwrap_or(n);

    let mut latest_path: Option<&str> = None;
    let mut latest_id: Option<&str> = None;
    for i in (0..limit).rev() {
        if is_user_list[i] && !paths[i].trim().is_empty() {
            latest_path = Some(&paths[i]);
            latest_id = Some(&entry_ids[i]);
            break;
        }
    }
    let lp = latest_path?;
    for i in 0..n {
        if is_user_list[i] && paths[i] == lp {
            return Some(entry_ids[i].clone());
        }
    }
    latest_id.map(str::to_owned)
}

pub fn plan_is_original_upload(
    entry_ids: &[String],
    is_user_list: &[bool],
    paths: &[String],
    message_id: &str,
) -> bool {
    let n = entry_ids.len().min(is_user_list.len()).min(paths.len());
    let mut seen = HashSet::new();
    for i in 0..n {
        if is_user_list[i] && !paths[i].trim().is_empty() && seen.insert(paths[i].as_str()) {
            if entry_ids[i] == message_id {
                return true;
            }
        }
    }
    false
}

pub fn plan_library_document_id(
    entry_ids: &[String],
    is_user_list: &[bool],
    paths: &[String],
    rag_doc_ids: &[String],
    turn_message_id: &str,
) -> Option<String> {
    let n = entry_ids
        .len()
        .min(is_user_list.len())
        .min(paths.len())
        .min(rag_doc_ids.len());
    let turn_index = (0..n).find(|&i| entry_ids[i] == turn_message_id && is_user_list[i])?;
    let candidate = rag_doc_ids[turn_index].trim();
    if candidate.is_empty() {
        return None;
    }

    let mut seen = HashSet::new();
    for i in 0..n {
        if is_user_list[i] && !paths[i].trim().is_empty() && seen.insert(paths[i].as_str()) {
            if entry_ids[i] == turn_message_id {
                return None;
            }
        }
    }
    seen.clear();
    for i in 0..n {
        if is_user_list[i] && !paths[i].trim().is_empty() && seen.insert(paths[i].as_str()) {
            if rag_doc_ids[i] == candidate {
                return None;
            }
        }
    }
    Some(candidate.to_owned())
}

// ============================================================================
// JNI Exports: RagRsNative
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_n_1findBreakPoint(
    mut env: JNIEnv,
    _: JClass,
    text: JString,
    start: jint,
    end: jint,
    chunk_size: jint,
    slot: JObjectArray,
) -> jint {
    guarded_slot(&mut env, &slot, |env| {
        let t = get_jstring(env, &text)?;
        Ok(find_break_point(&t, start, end, chunk_size))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_n_1fnv1a64(
    mut env: JNIEnv,
    _: JClass,
    text: JString,
    seed: jlong,
    slot: JObjectArray,
) -> jlong {
    guarded_slot(&mut env, &slot, |env| {
        let t = get_jstring(env, &text)?;
        Ok(fnv1a64(&t, seed))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_n_1isConversationOnly(
    mut env: JNIEnv,
    _: JClass,
    query: JString,
    slot: JObjectArray,
) -> jboolean {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring(env, &query)?;
        Ok(u8::from(is_conversation_only(&q)))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_n_1isUnqualifiedOverview(
    mut env: JNIEnv,
    _: JClass,
    query: JString,
    slot: JObjectArray,
) -> jboolean {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring(env, &query)?;
        Ok(u8::from(is_unqualified_overview(&q)))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_n_1hasLexicalEvidence(
    mut env: JNIEnv,
    _: JClass,
    query: JString,
    content: JString,
    slot: JObjectArray,
) -> jboolean {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring(env, &query)?;
        let c = get_jstring(env, &content)?;
        Ok(u8::from(has_lexical_evidence(&q, &c)))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_n_1scoreRelevance(
    mut env: JNIEnv,
    _: JClass,
    query: JString,
    content: JString,
    slot: JObjectArray,
) -> jdouble {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring(env, &query)?;
        let c = get_jstring(env, &content)?;
        Ok(score_relevance(&q, &c))
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1chunkText(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let text = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let chunk_size = r.i64().map_err(RustErr::illegal_arg)? as i32;
        let chunk_overlap = r.i64().map_err(RustErr::illegal_arg)? as i32;
        let chunks = chunk_text(&text, chunk_size, chunk_overlap)?;
        let mut w = WireWriter::new();
        w.string_list(&chunks);
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1chunkBoundaries(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let text = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let chunk_size = r.i64().map_err(RustErr::illegal_arg)? as i32;
        let chunk_overlap = r.i64().map_err(RustErr::illegal_arg)? as i32;
        let bounds = chunk_boundaries(&text, chunk_size, chunk_overlap)?;
        let mut w = WireWriter::new();
        w.ints_from_i32(&bounds);
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1dotProduct(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let a = r.doubles_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let b = r.doubles_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let mut w = WireWriter::new();
        w.f64(dot_product(&a, &b));
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1magnitude(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let v = r.doubles_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let mut w = WireWriter::new();
        w.f64(magnitude(&v));
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1l2Normalize(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let v = r.doubles_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let norm = l2_normalize(&v);
        let mut w = WireWriter::new();
        w.doubles(&norm);
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1cosineSimilarity(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let a = r.doubles_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let b = r.doubles_opt().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let mut w = WireWriter::new();
        w.f64(cosine_similarity(&a, &b));
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1embedUniversal(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let text = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let dim = r.i64().map_err(RustErr::illegal_arg)? as i32;
        let vec = embed_universal(&text, dim);
        let mut w = WireWriter::new();
        w.doubles(&vec);
        bytes_to_jbytearray(env, &w.buf)
    })
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_w_1planIsOriginalUpload(
    mut env: JNIEnv,
    _: JClass,
    args: JByteArray,
    slot: JObjectArray,
) -> jbyteArray {
    guarded_slot(&mut env, &slot, |env| {
        let bytes = jbytearray_to_vec(env, &args)?;
        let mut r = WireReader::new(&bytes);
        let entry_ids: Vec<String> = r
            .string_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or_default())
            .collect();
        let is_user_list: Vec<bool> = r
            .opt_bool_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or(false))
            .collect();
        let paths: Vec<String> = r
            .string_list_opt()
            .map_err(RustErr::illegal_arg)?
            .unwrap_or_default()
            .into_iter()
            .map(|o| o.unwrap_or_default())
            .collect();
        let message_id = r.str().map_err(RustErr::illegal_arg)?.unwrap_or_default();
        let res = plan_is_original_upload(&entry_ids, &is_user_list, &paths, &message_id);
        let mut w = WireWriter::new();
        w.bool(res);
        bytes_to_jbytearray(env, &w.buf)
    })
}

fn json_string_vec(v: &Value) -> Vec<String> {
    v.as_array()
        .map(|arr| {
            arr.iter()
                .map(|x| x.as_str().unwrap_or_default().to_owned())
                .collect()
        })
        .unwrap_or_default()
}

fn json_bool_vec(v: &Value) -> Vec<bool> {
    v.as_array()
        .map(|arr| arr.iter().map(|x| x.as_bool().unwrap_or(false)).collect())
        .unwrap_or_default()
}

#[no_mangle]
pub extern "system" fn Java_rustanything_RagRsNative_call(
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
            "planSavedUploadId" => {
                let entry_ids = arr.first().map(json_string_vec).unwrap_or_default();
                let is_user = arr.get(1).map(json_bool_vec).unwrap_or_default();
                let paths = arr.get(2).map(json_string_vec).unwrap_or_default();
                let turn_id = arr.get(3).and_then(|v| v.as_str());
                let res = plan_saved_upload_id(&entry_ids, &is_user, &paths, turn_id);
                Ok(serde_json::json!({ "ok": res }))
            }
            "planLibraryDocumentId" => {
                let entry_ids = arr.first().map(json_string_vec).unwrap_or_default();
                let is_user = arr.get(1).map(json_bool_vec).unwrap_or_default();
                let paths = arr.get(2).map(json_string_vec).unwrap_or_default();
                let rag_ids = arr.get(3).map(json_string_vec).unwrap_or_default();
                let turn_id = arr.get(4).and_then(|v| v.as_str()).unwrap_or_default();
                let res = plan_library_document_id(&entry_ids, &is_user, &paths, &rag_ids, turn_id);
                Ok(serde_json::json!({ "ok": res }))
            }
            other => Err(format!("UnsupportedOperationException: Unknown RagRsNative call '{other}'")),
        }
    }));

    let payload = match outcome {
        Ok(Ok(v)) => v.to_string(),
        Ok(Err(err)) => serde_json::json!({ "error": err }).to_string(),
        Err(_) => serde_json::json!({ "panic": "Rust panic in RagRsNative.call" }).to_string(),
    };
    env.new_string(payload)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ============================================================================
// JNI Exports: ScoreRelevanceBatch & HasLexicalEvidenceBatch
// ============================================================================

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_ScoreRelevanceBatch_nNew(
    mut env: JNIEnv,
    _: JClass,
    items: JObjectArray,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| -> Result<jlong, RustErr> {
        let vec = read_string_array(&mut env, &items)?;
        let handle = NEXT_BATCH_HANDLE.fetch_add(1, Ordering::Relaxed);
        let idx = BatchIndex::from_items(vec);
        batch_store()
            .lock()
            .map_err(|_| RustErr::illegal_state("Batch store lock poisoned"))?
            .insert(handle, idx);
        Ok(handle)
    })) {
        Ok(Ok(h)) => h,
        Ok(Err(e)) => {
            let _ = env.throw_new("java/lang/RuntimeException", e.message);
            0
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/RuntimeException", "Panic in ScoreRelevanceBatch.nNew");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_ScoreRelevanceBatch_nFree(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    if let Ok(mut guard) = batch_store().lock() {
        guard.remove(&handle);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_ScoreRelevanceBatch_nRun(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    query: JString,
    slot: JObjectArray,
) -> jdoubleArray {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring_opt(env, &query)?.unwrap_or_default();
        let scores = {
            let guard = batch_store()
                .lock()
                .map_err(|_| RustErr::illegal_state("Batch store lock poisoned"))?;
            let batch = guard
                .get(&handle)
                .ok_or_else(|| RustErr::illegal_state("ScoreRelevanceBatch is closed"))?;
            batch.score_all(&q)
        };
        let arr = env
            .new_double_array(scores.len() as i32)
            .map_err(|e| RustErr::runtime(format!("new_double_array failed: {e}")))?;
        env.set_double_array_region(&arr, 0, &scores)
            .map_err(|e| RustErr::runtime(format!("set_double_array_region failed: {e}")))?;
        Ok(arr.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_ScoreRelevanceBatch_nRank(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    query: JString,
    min: jdouble,
    slot: JObjectArray,
) -> jintArray {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring_opt(env, &query)?.unwrap_or_default();
        let scores = {
            let guard = batch_store()
                .lock()
                .map_err(|_| RustErr::illegal_state("Batch store lock poisoned"))?;
            let batch = guard
                .get(&handle)
                .ok_or_else(|| RustErr::illegal_state("ScoreRelevanceBatch is closed"))?;
            batch.score_all(&q)
        };
        let mut ranked: Vec<(usize, f64)> = scores
            .into_iter()
            .enumerate()
            .filter(|(_, s)| *s >= min)
            .collect();
        ranked.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
        let indices: Vec<i32> = ranked.into_iter().map(|(i, _)| i as i32).collect();
        let arr = env
            .new_int_array(indices.len() as i32)
            .map_err(|e| RustErr::runtime(format!("new_int_array failed: {e}")))?;
        env.set_int_array_region(&arr, 0, &indices)
            .map_err(|e| RustErr::runtime(format!("set_int_array_region failed: {e}")))?;
        Ok(arr.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_HasLexicalEvidenceBatch_nNew(
    env: JNIEnv,
    _: JClass,
    items: JObjectArray,
) -> jlong {
    Java_com_shounak_localmeshai_rag_ScoreRelevanceBatch_nNew(env, JClass::default(), items)
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_HasLexicalEvidenceBatch_nFree(
    env: JNIEnv,
    cls: JClass,
    handle: jlong,
) {
    Java_com_shounak_localmeshai_rag_ScoreRelevanceBatch_nFree(env, cls, handle);
}

#[no_mangle]
pub extern "system" fn Java_com_shounak_localmeshai_rag_HasLexicalEvidenceBatch_nRun(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    query: JString,
    slot: JObjectArray,
) -> jbooleanArray {
    guarded_slot(&mut env, &slot, |env| {
        let q = get_jstring_opt(env, &query)?.unwrap_or_default();
        let flags = {
            let guard = batch_store()
                .lock()
                .map_err(|_| RustErr::illegal_state("Batch store lock poisoned"))?;
            let batch = guard
                .get(&handle)
                .ok_or_else(|| RustErr::illegal_state("HasLexicalEvidenceBatch is closed"))?;
            batch.has_lexical_evidence_all(&q)
        };
        let raw: Vec<u8> = flags.into_iter().map(u8::from).collect();
        let arr = env
            .new_boolean_array(raw.len() as i32)
            .map_err(|e| RustErr::runtime(format!("new_boolean_array failed: {e}")))?;
        env.set_boolean_array_region(&arr, 0, &raw)
            .map_err(|e| RustErr::runtime(format!("set_boolean_array_region failed: {e}")))?;
        Ok(arr.into_raw())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunk_text_respects_boundaries_and_validation() {
        assert!(chunk_text("hello", 0, 0).is_err());
        assert!(chunk_text("hello", 10, 10).is_err());
        let text = "First sentence here. Second sentence follows. Third sentence ends the doc.";
        let chunks = chunk_text(text, 40, 10).unwrap();
        assert!(chunks.len() >= 2);
        assert!(chunks[0].starts_with("First"));
    }

    #[test]
    fn embed_universal_is_normalized_and_deterministic() {
        let v1 = embed_universal("Solus local offline AI", 128);
        let v2 = embed_universal("Solus local offline AI", 128);
        assert_eq!(v1, v2);
        assert_eq!(v1.len(), 128);
        let mag = magnitude(&v1);
        assert!((mag - 1.0).abs() < 1e-6);
        assert!((cosine_similarity(&v1, &v2) - 1.0).abs() < 1e-6);
    }

    #[test]
    fn relevance_and_batch_ranking_work() {
        assert!(is_conversation_only("Hello there!"));
        assert!(is_unqualified_overview("Give me a summary"));
        assert!(has_lexical_evidence("What is Rust ownership?", "Rust uses ownership and borrowing."));
        let idx = BatchIndex::from_items(vec![
            "Apples and bananas".into(),
            "Rust memory safety and ownership".into(),
            "Rustacean guide".into(),
        ]);
        let ev = idx.has_lexical_evidence_all("rust ownership");
        assert_eq!(ev, vec![false, true, false]);
        let scores = idx.score_all("rust ownership");
        assert!(scores[1] > scores[2] && scores[2] > scores[0]);
    }

    #[test]
    fn turn_document_planning_matches_kotlin() {
        let ids = vec!["u1".into(), "a1".into(), "u2".into()];
        let is_user = vec![true, false, true];
        let paths = vec!["/tmp/a.pdf".into(), "".into(), "".into()];
        assert_eq!(plan_saved_upload_id(&ids, &is_user, &paths, Some("u2")), Some("u1".into()));
        assert!(plan_is_original_upload(&ids, &is_user, &paths, "u1"));
        assert!(!plan_is_original_upload(&ids, &is_user, &paths, "u2"));
    }
}
