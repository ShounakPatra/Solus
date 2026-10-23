//! Local media pipelines with explicit model families and preprocessing contracts.
use anyhow::{anyhow, bail, Result};
use candle_core::{DType, Device, IndexOp, Tensor};
use candle_nn::{Module, VarBuilder};
use candle_transformers::models::{stable_diffusion as sd, vit, whisper, paligemma};
use std::{path::{Path, PathBuf}, sync::atomic::{AtomicBool, Ordering}};
use tokenizers::Tokenizer;

pub enum MediaModel {
    VisionLanguage { model: paligemma::Model, tokenizer: Tokenizer, size: usize, eos: Vec<u32>, context: usize, image_tokens: usize },
    Vit { model: vit::Model, size: usize },
    Whisper { model: whisper::model::Whisper, tokenizer: Tokenizer, filters: Vec<f32> },
    Diffusion { config: sd::StableDiffusionConfig, clip: sd::clip::ClipTextTransformer,
        unet: sd::unet_2d::UNet2DConditionModel, vae: sd::vae::AutoEncoderKL, tokenizer: Tokenizer, v_prediction: bool },
}
fn json(path: impl AsRef<Path>) -> Result<serde_json::Value> { Ok(serde_json::from_reader(std::fs::File::open(path)?)?) }
fn eos_tokens(config: &serde_json::Value, generation: Option<&serde_json::Value>) -> Result<Vec<u32>> {
    let value = generation.map(|v| &v["eos_token_id"]).into_iter().chain([&config["eos_token_id"], &config["text_config"]["eos_token_id"]])
        .find(|value| !value.is_null()).ok_or_else(|| anyhow!("Missing vision decoder EOS tokens"))?;
    let values = value.as_array().cloned().unwrap_or_else(|| vec![value.clone()]);
    let ids: Vec<u32> = values.iter().map(|id| Ok(u32::try_from(id.as_u64().ok_or_else(|| anyhow!("Invalid EOS token"))?)?)).collect::<Result<_>>()?;
    if ids.is_empty() { bail!("Empty EOS token list"); }
    Ok(ids)
}
fn component(root: &Path, name: &str) -> Result<PathBuf> {
    let nested = root.join(name);
    if nested.is_file() { return Ok(nested); }
    let flat = root.join(name.replace('/', "--"));
    if flat.is_file() { return Ok(flat); }
    bail!("Missing pipeline component {name}")
}
fn active(stopped: &AtomicBool) -> Result<()> {
    if stopped.load(Ordering::Relaxed) { bail!("Generation cancelled"); }
    Ok(())
}
impl MediaModel {
    pub fn load(root: &Path, paths: &[PathBuf], task: &str) -> Result<Self> {
        let device = &Device::Cpu;
        if task == "image_generation" {
            let index = json(root.join("model_index.json"))?;
            if index["_class_name"] != "StableDiffusionPipeline" { bail!("Only Diffusers StableDiffusionPipeline v1/v2 is supported by this image adapter"); }
            let text_config = json(component(root, "text_encoder/config.json")?)?;
            let config = match text_config["hidden_size"].as_u64() {
                Some(768) => sd::StableDiffusionConfig::v1_5(Some(1), Some(256), Some(256)),
                Some(1024) => sd::StableDiffusionConfig::v2_1(Some(1), Some(256), Some(256)),
                _ => bail!("Unsupported Stable Diffusion text encoder"),
            };
            let clip = sd::build_clip_transformer(&config.clip, component(root, "text_encoder/model.safetensors")?, device, DType::F32)?;
            let unet = config.build_unet(component(root, "unet/diffusion_pytorch_model.safetensors")?, device, 4, false, DType::F32)?;
            let vae = config.build_vae(component(root, "vae/diffusion_pytorch_model.safetensors")?, device, DType::F32)?;
            let tokenizer = crate::tokenizer_support::load(&root.join("tokenizer"), true)?;
            let scheduler_path = component(root, "scheduler/scheduler_config.json")?;
            let scheduler = json(scheduler_path)?;
            let v_prediction = match scheduler["prediction_type"].as_str().unwrap_or("epsilon") {
                "epsilon" => false, "v_prediction" => true, other => bail!("Unsupported diffusion prediction type {other}"),
            };
            return Ok(Self::Diffusion { config, clip, unet, vae, tokenizer, v_prediction });
        }
        let config = json(root.join("config.json"))?;
        let vb = unsafe { VarBuilder::from_mmaped_safetensors(paths, DType::F32, device)? };
        Ok(match config["model_type"].as_str().unwrap_or("") {
            "paligemma" if task == "vision_language" => {
                if config["text_config"]["model_type"].as_str().unwrap_or("gemma") != "gemma" { bail!("Only original Gemma-based PaLiGemma is supported"); }
                let cfg: paligemma::Config = serde_json::from_value(config.clone())?;
                let size = cfg.vision_config.image_size;
                let patch = cfg.vision_config.patch_size;
                if patch == 0 || size % patch != 0 { bail!("Invalid PaLiGemma image patch size"); }
                let generation = root.join("generation_config.json");
                let generation = if generation.is_file() { Some(json(generation)?) } else { None };
                let eos = eos_tokens(&config, generation.as_ref())?;
                let context = cfg.text_config.max_position_embeddings;
                let tokenizer = crate::tokenizer_support::load(root, false)?;
                Self::VisionLanguage { model: paligemma::Model::new(&cfg, vb)?, tokenizer, size, eos, context, image_tokens: (size/patch).pow(2) }
            }
            "vit" if task == "image_classification" => {
                let cfg: vit::Config = serde_json::from_value(config.clone())?;
                if cfg.num_channels != 3 { bail!("ViT requires three RGB channels"); }
                let labels = config["id2label"].as_object().map(|map| map.len()).filter(|v| *v > 0)
                    .or(config["num_labels"].as_u64().map(|v| v as usize)).ok_or_else(|| anyhow!("ViT is missing its label count"))?;
                Self::Vit { model: vit::Model::new(&cfg, labels, vb)?, size: cfg.image_size }
            }
            "whisper" if task == "speech_recognition" => {
                let cfg: whisper::Config = serde_json::from_value(config)?;
                if ![80, 128].contains(&cfg.num_mel_bins) { bail!("Unsupported Whisper mel dimensions"); }
                let filters = mel_filters(cfg.num_mel_bins);
                let tokenizer = Tokenizer::from_file(root.join("tokenizer.json")).map_err(|e| anyhow!(e.to_string()))?;
                Self::Whisper { model: whisper::model::Whisper::load(&vb, cfg)?, tokenizer, filters }
            }
            architecture => bail!("No {task} safetensors adapter for '{architecture}'"),
        })
    }
    pub fn answer_image(&mut self, pixels: Vec<f32>, prompt: &str, stopped: &AtomicBool) -> Result<String> {
        let Self::VisionLanguage { model, tokenizer, size, eos, context, image_tokens } = self else { bail!("Not a vision-language model"); };
        active(stopped)?;
        let prompt = format!("{}\n", prompt.trim());
        let ids = tokenizer.encode(prompt, true).map_err(|e| anyhow!(e.to_string()))?.get_ids().to_vec();
        let prefix = ids.len() + *image_tokens;
        if ids.is_empty() || prefix >= *context { bail!("Image and prompt exceed model context"); }
        let pixels = Tensor::from_vec(pixels, (1,3,*size,*size), &Device::Cpu)?;
        let ids = Tensor::new(ids.as_slice(), &Device::Cpu)?.unsqueeze(0)?;
        let mut logits = model.setup(&pixels, &ids)?;
        let mut generated = Vec::new();
        for _ in 0..256.min(*context-prefix) {
            active(stopped)?;
            let vocab = *logits.dims().last().ok_or_else(|| anyhow!("Missing vision logits dimensions"))?;
            let all_scores = logits.flatten_all()?.to_vec1::<f32>()?;
            let scores = &all_scores[all_scores.len()-vocab..];
            let token = scores.iter().enumerate().filter(|(_,v)| v.is_finite()).max_by(|a,b| a.1.total_cmp(b.1))
                .ok_or_else(|| anyhow!("Model produced no finite scores"))?.0 as u32;
            if eos.contains(&token) { break; }
            generated.push(token);
            logits = model.forward(&Tensor::new(&[token], &Device::Cpu)?.unsqueeze(0)?)?;
        }
        active(stopped)?;
        Ok(tokenizer.decode(&generated, true).map_err(|e| anyhow!(e.to_string()))?)
    }
    pub fn classify(&self, pixels: Vec<f32>, stopped: &AtomicBool) -> Result<Vec<f32>> {
        active(stopped)?;
        let Self::Vit { model, size } = self else { bail!("This is not an image classifier"); };
        let input = Tensor::from_vec(pixels, (1, 3, *size, *size), &Device::Cpu)?;
        let result = model.forward(&input)?.flatten_all()?.to_vec1::<f32>()?;
        active(stopped)?;
        Ok(result)
    }
    pub fn transcribe(&mut self, samples: &[f32], stopped: &AtomicBool) -> Result<String> {
        let Self::Whisper { model, tokenizer, filters } = self else { bail!("This is not a speech recognition model"); };
        if samples.is_empty() || samples.len() > whisper::N_SAMPLES { bail!("Whisper accepts up to 30 seconds of 16 kHz mono audio per request"); }
        active(stopped)?;
        model.reset_kv_cache();
        let mut pcm = samples.to_vec();
        pcm.resize(whisper::N_SAMPLES, 0.0);
        let mel = whisper::audio::pcm_to_mel(&model.config, &pcm, filters);
        let frames = mel.len() / model.config.num_mel_bins;
        let mel = Tensor::from_vec(mel, (1, model.config.num_mel_bins, frames), &Device::Cpu)?
            .narrow(2, 0, model.config.max_source_positions * 2)?;
        let features = model.encoder.forward(&mel, true)?;
        active(stopped)?;
        let token = |name: &str| tokenizer.token_to_id(name).ok_or_else(|| anyhow!("Missing Whisper token {name}"));
        let start = token(whisper::SOT_TOKEN)?;
        let eos = token(whisper::EOT_TOKEN)?;
        let no_timestamps = token(whisper::NO_TIMESTAMPS_TOKEN)?;
        let mut tokens = vec![start];
        if model.config.vocab_size >= 51865 {
            let input = Tensor::new(&[start], &Device::Cpu)?.unsqueeze(0)?;
            let language_logits = model.decoder.forward(&input, &features, true)?;
            let language_logits = model.decoder.final_linear(&language_logits)?.flatten_all()?.to_vec1::<f32>()?;
            let language = tokenizer.get_vocab(true).into_iter().filter(|(name, _)| {
                name.starts_with("<|") && name.ends_with("|>") && (6..=7).contains(&name.len()) &&
                    name[2..name.len()-2].bytes().all(|c| c.is_ascii_lowercase())
            }).map(|(_, id)| id).filter(|id| (*id as usize) < language_logits.len())
                .max_by(|a,b| language_logits[*a as usize].total_cmp(&language_logits[*b as usize])).ok_or_else(|| anyhow!("No Whisper language tokens"))?;
            tokens.push(language);
        }
        tokens.push(token(whisper::TRANSCRIBE_TOKEN)?);
        tokens.push(no_timestamps);
        let prefix = tokens.len();
        for _ in 0..model.config.max_target_positions.saturating_sub(prefix).min(448) {
            active(stopped)?;
            let input = Tensor::new(tokens.as_slice(), &Device::Cpu)?.unsqueeze(0)?;
            let decoded = model.decoder.forward(&input, &features, true)?;
            let last = decoded.i((.., tokens.len()-1, ..))?.unsqueeze(1)?;
            let logits = model.decoder.final_linear(&last)?.flatten_all()?.to_vec1::<f32>()?;
            let next = logits.iter().enumerate().filter(|(i,v)| v.is_finite() && (*i as u32) <= no_timestamps && !model.config.suppress_tokens.contains(&(*i as u32)))
                .max_by(|a,b| a.1.total_cmp(b.1)).ok_or_else(|| anyhow!("Whisper produced no valid logits"))?.0 as u32;
            if next == eos { break; }
            tokens.push(next);
        }
        Ok(tokenizer.decode(&tokens[prefix..], true).map_err(|e| anyhow!(e.to_string()))?)
    }
    pub fn generate_image(&self, prompt: &str, stopped: &AtomicBool) -> Result<Vec<f32>> {
        let Self::Diffusion { config, clip, unet, vae, tokenizer, v_prediction } = self else { bail!("This is not an image generation pipeline"); };
        active(stopped)?;
        let encode = |text| -> Result<Tensor> {
            let mut ids = tokenizer.encode(text, true).map_err(|e| anyhow!(e.to_string()))?.get_ids().to_vec();
            let eos = tokenizer.token_to_id("<|endoftext|>").ok_or_else(|| anyhow!("Missing CLIP end token"))?;
            let pad = match config.clip.pad_with.as_deref() {
                Some(token) => tokenizer.token_to_id(token).ok_or_else(|| anyhow!("Missing CLIP padding token"))?, None => eos,
            };
            if ids.len() >= config.clip.max_position_embeddings {
                ids.truncate(config.clip.max_position_embeddings);
                if let Some(last) = ids.last_mut() { *last = eos; }
            }
            ids.resize(config.clip.max_position_embeddings, pad);
            Ok(clip.forward(&Tensor::new(ids.as_slice(), &Device::Cpu)?.unsqueeze(0)?)?)
        };
        let embeddings = Tensor::cat(&[encode("")?, encode(prompt)?], 0)?;
        use sd::{ddim::DDIMSchedulerConfig, schedulers::{PredictionType, SchedulerConfig}};
        let mut scheduler = DDIMSchedulerConfig { prediction_type: if *v_prediction { PredictionType::VPrediction } else { PredictionType::Epsilon }, ..Default::default() }.build(20)?;
        let mut latents = (Tensor::randn(0f32, 1f32, (1, 4, config.height/8, config.width/8), &Device::Cpu)? * scheduler.init_noise_sigma())?;
        for &timestep in scheduler.timesteps().to_vec().iter() {
            active(stopped)?;
            let input = scheduler.scale_model_input(Tensor::cat(&[&latents, &latents], 0)?, timestep)?;
            let prediction = unet.forward(&input, timestep as f64, &embeddings)?;
            let parts = prediction.chunk(2, 0)?;
            let guided = (&parts[0] + ((&parts[1] - &parts[0])? * 7.5)?)?;
            latents = scheduler.step(&guided, timestep, &latents)?;
        }
        active(stopped)?;
        let decoded = vae.decode(&(latents / 0.18215)?)?;
        active(stopped)?;
        Ok(((decoded / 2.0)? + 0.5)?.flatten_all()?.to_vec1::<f32>()?)
    }
}

/// Slaney triangular mel filterbank used by librosa/Whisper, 16 kHz and 400-point FFT.
pub fn mel_filters(count: usize) -> Vec<f32> {
    let hz_to_mel = |hz: f64| if hz < 1000.0 { hz / (200.0/3.0) } else { 15.0 + (hz/1000.0).ln()/(6.4f64.ln()/27.0) };
    let mel_to_hz = |mel: f64| if mel < 15.0 { mel*(200.0/3.0) } else { 1000.0*((mel-15.0)*(6.4f64.ln()/27.0)).exp() };
    let edges: Vec<f64> = (0..count+2).map(|i| mel_to_hz(hz_to_mel(8000.0)*i as f64/(count+1) as f64)).collect();
    let mut filters = Vec::with_capacity(count*201);
    for i in 0..count {
        for bin in 0..201 {
            let hz = bin as f64*40.0;
            let lower = (hz-edges[i])/(edges[i+1]-edges[i]);
            let upper = (edges[i+2]-hz)/(edges[i+2]-edges[i+1]);
            filters.push((lower.min(upper).max(0.0)*2.0/(edges[i+2]-edges[i])) as f32);
        }
    }
    filters
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn vision_eos_uses_generation_config_and_nested_gemma_defaults() -> Result<()> {
        let cfg = serde_json::json!({"text_config":{"eos_token_id":1}});
        assert_eq!(eos_tokens(&cfg,None)?, vec![1]);
        assert_eq!(eos_tokens(&cfg,Some(&serde_json::json!({"eos_token_id":[1,7]})))?,vec![1,7]);
        assert!(eos_tokens(&serde_json::json!({}),None).is_err());
        assert!(eos_tokens(&cfg,Some(&serde_json::json!({"eos_token_id":[]}))).is_err());
        Ok(())
    }
}
