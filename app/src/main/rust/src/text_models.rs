//! Explicit architecture dispatch: weights never determine executable code by extension alone.
use anyhow::{bail, Result};
use candle_core::{DType, Device, Tensor};
use candle_nn::VarBuilder;
use candle_transformers::models::{gemma, llama, mistral, phi, phi3, qwen2};

pub enum TextModel {
    Qwen(qwen2::ModelForCausalLM),
    Gpt2(crate::gpt2::Gpt2),
    Llama { model: llama::Llama, cache: llama::Cache, config: llama::Config },
    Mistral(mistral::Model),
    Gemma(gemma::Model),
    Phi(phi::Model),
    Phi3(phi3::Model),
}

impl TextModel {
    pub fn load(mut config: serde_json::Value, vb: VarBuilder, context: usize) -> Result<Self> {
        let architecture = config["model_type"].as_str().unwrap_or("").to_owned();
        if !config["quantization_config"].is_null() { bail!("Prequantized {architecture} weights require a quantization-specific adapter"); }
        if !config["rope_scaling"].is_null() && architecture != "llama" {
            bail!("Scaled RoPE for {architecture} is not supported by this adapter");
        }
        if let Some(max) = config["max_position_embeddings"].as_u64() {
            config["max_position_embeddings"] = (max as usize).min(context).into();
        }
        Ok(match architecture.as_str() {
            "gpt2" => Self::Gpt2(crate::gpt2::Gpt2::load(&config, vb, context)?),
            "qwen2" => {
                if config["sliding_window"].is_null() || config["use_sliding_window"].as_bool() != Some(true) {
                    config["sliding_window"] = config["max_position_embeddings"].clone();
                }
                if config["max_window_layers"].is_null() { config["max_window_layers"] = config["num_hidden_layers"].clone(); }
                if config["use_sliding_window"].is_null() { config["use_sliding_window"] = false.into(); }
                Self::Qwen(qwen2::ModelForCausalLM::new(&serde_json::from_value(config)?, vb)?)
            }
            "llama" => {
                let cfg: llama::LlamaConfig = serde_json::from_value(config)?;
                let cfg = cfg.into_config(false);
                let cache = llama::Cache::new(true, DType::F32, &cfg, &Device::Cpu)?;
                Self::Llama { model: llama::Llama::load(vb, &cfg)?, cache, config: cfg }
            }
            "mistral" => Self::Mistral(mistral::Model::new(&serde_json::from_value(config)?, vb)?),
            "gemma" => {
                // HF exports sometimes retain the deprecated activation alias too.
                if !config["hidden_activation"].is_null() { config["hidden_act"] = serde_json::Value::Null; }
                Self::Gemma(gemma::Model::new(false, &serde_json::from_value(config)?, vb)?)
            }
            "phi" => Self::Phi(phi::Model::new(&serde_json::from_value(config)?, vb)?),
            "phi3" => Self::Phi3(phi3::Model::new(&serde_json::from_value(config)?, vb)?),
            _ => bail!("No safetensors text adapter for architecture '{architecture}'"),
        })
    }

    pub fn forward(&mut self, input: &Tensor, position: usize) -> Result<Tensor> {
        Ok(match self {
            Self::Gpt2(model) => model.forward(input, position)?,
            Self::Qwen(model) => model.forward(input, position)?,
            Self::Llama { model, cache, .. } => model.forward(input, position, cache)?,
            Self::Mistral(model) => model.forward(input, position)?,
            Self::Gemma(model) => model.forward(input, position)?,
            Self::Phi(model) => model.forward(input)?,
            Self::Phi3(model) => model.forward(input, position)?,
        })
    }

    pub fn reset(&mut self) -> Result<()> {
        match self {
            Self::Gpt2(model) => model.reset(),
            Self::Qwen(model) => model.clear_kv_cache(),
            Self::Llama { cache, config, .. } => *cache = llama::Cache::new(true, DType::F32, config, &Device::Cpu)?,
            Self::Mistral(model) => model.clear_kv_cache(),
            Self::Gemma(model) => model.clear_kv_cache(),
            Self::Phi(model) => model.clear_kv_cache(),
            Self::Phi3(model) => model.clear_kv_cache(),
        }
        Ok(())
    }
}
