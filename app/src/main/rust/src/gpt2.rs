use anyhow::{bail, Result};
use candle_core::{Device, IndexOp, Tensor};
use candle_nn::{Embedding, LayerNorm, Linear, Module, VarBuilder};

struct Attention {
    qkv: Linear, projection: Linear, heads: usize, head_dim: usize,
    cache: Option<(Tensor, Tensor)>, scale: f64,
}
struct Block { norm1: LayerNorm, norm2: LayerNorm, attention: Attention, fc: Linear, projection: Linear }
pub struct Gpt2 { embeddings: Embedding, positions: Embedding, blocks: Vec<Block>, norm: LayerNorm, head: Linear, context: usize }

fn conv1d(input: usize, output: usize, vb: VarBuilder) -> Result<Linear> {
    // Hugging Face GPT-2's Conv1D stores [input, output], unlike nn.Linear.
    Ok(Linear::new(vb.get((input, output), "weight")?.t()?.contiguous()?, Some(vb.get(output, "bias")?)))
}
impl Gpt2 {
    pub fn load(config: &serde_json::Value, vb: VarBuilder, context: usize) -> Result<Self> {
        let number = |key: &str| -> Result<usize> {
            let value = config[key].as_u64().filter(|v| *v > 0).ok_or_else(|| anyhow::anyhow!("Invalid GPT-2 {key}"))?;
            Ok(usize::try_from(value)?)
        };
        let hidden = number("n_embd")?;
        let heads = number("n_head")?;
        let layers = number("n_layer")?;
        let vocab = number("vocab_size")?;
        let max = number("n_positions")?;
        if hidden % heads != 0 || config["add_cross_attention"].as_bool() == Some(true) { bail!("Unsupported GPT-2 attention configuration"); }
        let activation = config["activation_function"].as_str().unwrap_or("gelu_new");
        if activation != "gelu_new" && activation != "gelu_pytorch_tanh" { bail!("Unsupported GPT-2 activation: {activation}"); }
        let inner = config["n_inner"].as_u64().map(|v| v as usize).unwrap_or(hidden * 4);
        let eps = config["layer_norm_epsilon"].as_f64().unwrap_or(1e-5);
        let root = if vb.contains_tensor("transformer.wte.weight") { vb.pp("transformer") } else { vb.clone() };
        let embeddings = candle_nn::embedding(vocab, hidden, root.pp("wte"))?;
        let positions = candle_nn::embedding(max, hidden, root.pp("wpe"))?;
        let mut blocks = Vec::with_capacity(layers);
        for layer in 0..layers {
            let layer_vb = root.pp(format!("h.{layer}"));
            let mut scale = if config["scale_attn_weights"].as_bool() == Some(false) { 1.0 } else { 1.0 / ((hidden / heads) as f64).sqrt() };
            if config["scale_attn_by_inverse_layer_idx"].as_bool() == Some(true) { scale /= (layer + 1) as f64; }
            blocks.push(Block {
                norm1: candle_nn::layer_norm(hidden, eps, layer_vb.pp("ln_1"))?,
                norm2: candle_nn::layer_norm(hidden, eps, layer_vb.pp("ln_2"))?,
                attention: Attention { qkv: conv1d(hidden, hidden * 3, layer_vb.pp("attn.c_attn"))?,
                    projection: conv1d(hidden, hidden, layer_vb.pp("attn.c_proj"))?, heads, head_dim: hidden / heads, cache: None, scale },
                fc: conv1d(hidden, inner, layer_vb.pp("mlp.c_fc"))?,
                projection: conv1d(inner, hidden, layer_vb.pp("mlp.c_proj"))?,
            });
        }
        let norm = candle_nn::layer_norm(hidden, eps, root.pp("ln_f"))?;
        let head = if vb.contains_tensor("lm_head.weight") {
            candle_nn::linear_no_bias(hidden, vocab, vb.pp("lm_head"))?
        } else { Linear::new(embeddings.embeddings().clone(), None) };
        Ok(Self { embeddings, positions, blocks, norm, head, context: context.min(max) })
    }
    pub fn reset(&mut self) { for block in &mut self.blocks { block.attention.cache = None; } }
    pub fn forward(&mut self, input: &Tensor, position: usize) -> Result<Tensor> {
        let (batch, length) = input.dims2()?;
        if length == 0 || position + length > self.context { bail!("GPT-2 context capacity exceeded"); }
        let positions = Tensor::arange(position as u32, (position + length) as u32, &Device::Cpu)?.unsqueeze(0)?;
        let mut hidden = self.embeddings.forward(input)?.broadcast_add(&self.positions.forward(&positions)?)?;
        for block in &mut self.blocks {
            let attention = &mut block.attention;
            let qkv = attention.qkv.forward(&block.norm1.forward(&hidden)?)?;
            let width = attention.heads * attention.head_dim;
            let split = |index| -> Result<Tensor> {
                Ok(qkv.narrow(2, index * width, width)?.reshape((batch, length, attention.heads, attention.head_dim))?.transpose(1, 2)?.contiguous()?)
            };
            let query = split(0)?;
            let mut key = split(1)?;
            let mut value = split(2)?;
            if let Some((past_k, past_v)) = &attention.cache {
                if past_k.dim(2)? != position { bail!("GPT-2 cache position mismatch"); }
                key = Tensor::cat(&[past_k, &key], 2)?;
                value = Tensor::cat(&[past_v, &value], 2)?;
            } else if position != 0 { bail!("GPT-2 cache must start at zero"); }
            attention.cache = Some((key.clone(), value.clone()));
            let total = key.dim(2)?;
            let mask: Vec<f32> = (0..length).flat_map(|q| (0..total).map(move |k| if k > position + q { f32::NEG_INFINITY } else { 0.0 })).collect();
            let mask = Tensor::from_vec(mask, (1, 1, length, total), &Device::Cpu)?;
            let scores = (query.matmul(&key.transpose(2, 3)?.contiguous()?)? * attention.scale)?.broadcast_add(&mask)?;
            let attended = candle_nn::ops::softmax_last_dim(&scores)?.matmul(&value.contiguous()?)?
                .transpose(1, 2)?.contiguous()?.reshape((batch, length, width))?;
            hidden = (hidden + attention.projection.forward(&attended)?)?;
            let mlp = block.fc.forward(&block.norm2.forward(&hidden)?)?;
            let mlp = candle_nn::Activation::GeluPytorchTanh.forward(&mlp)?;
            hidden = (hidden + block.projection.forward(&mlp)?)?;
        }
        Ok(self.head.forward(&self.norm.forward(&hidden)?.i((.., length - 1, ..))?)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    fn fixture() -> (serde_json::Value, HashMap<String, Tensor>) {
        let config = serde_json::json!({"model_type":"gpt2", "n_embd":8, "n_head":2, "n_layer":2,
            "n_inner":16, "vocab_size":13, "n_positions":16, "activation_function":"gelu_new"});
        let mut weights = HashMap::new();
        let mut insert = |name: String, shape: Vec<usize>, constant: Option<f32>| {
            let count: usize = shape.iter().product();
            let values = (0..count).map(|i| constant.unwrap_or(((i as f32 + name.len() as f32)*0.37).sin()*0.15)).collect::<Vec<_>>();
            weights.insert(name, Tensor::from_vec(values, shape.as_slice(), &Device::Cpu).unwrap());
        };
        insert("transformer.wte.weight".into(), vec![13,8], None);
        insert("transformer.wpe.weight".into(), vec![16,8], None);
        insert("transformer.ln_f.weight".into(), vec![8], Some(1.0));
        insert("transformer.ln_f.bias".into(), vec![8], Some(0.0));
        for layer in 0..2 {
            for norm in ["ln_1", "ln_2"] {
                insert(format!("transformer.h.{layer}.{norm}.weight"), vec![8], Some(1.0));
                insert(format!("transformer.h.{layer}.{norm}.bias"), vec![8], Some(0.0));
            }
            for (name,input,output) in [("attn.c_attn",8,24),("attn.c_proj",8,8),("mlp.c_fc",8,16),("mlp.c_proj",16,8)] {
                insert(format!("transformer.h.{layer}.{name}.weight"), vec![input,output], None);
                insert(format!("transformer.h.{layer}.{name}.bias"), vec![output], Some(0.0));
            }
        }
        (config, weights)
    }
    #[test]
    fn gpt2_cached_decoding_matches_full_prefix_and_reset() -> Result<()> {
        let (config, weights) = fixture();
        let mut model = Gpt2::load(&config, VarBuilder::from_tensors(weights, candle_core::DType::F32, &Device::Cpu), 16)?;
        let full = Tensor::new(&[[1u32,2,3,4]], &Device::Cpu)?;
        let expected = model.forward(&full, 0)?.flatten_all()?.to_vec1::<f32>()?;
        model.reset();
        model.forward(&Tensor::new(&[[1u32,2]], &Device::Cpu)?, 0)?;
        model.forward(&Tensor::new(&[[3u32]], &Device::Cpu)?, 2)?;
        let actual = model.forward(&Tensor::new(&[[4u32]], &Device::Cpu)?, 3)?.flatten_all()?.to_vec1::<f32>()?;
        assert_eq!(actual.len(),13);
        for (a,b) in actual.iter().zip(&expected) { assert!((a-b).abs()<1e-4, "cached={a}, full={b}"); }
        model.reset();
        let reset = model.forward(&full, 0)?.flatten_all()?.to_vec1::<f32>()?;
        assert_eq!(expected, reset);
        Ok(())
    }
    #[test]
    fn hf_conv1d_weights_are_transposed() -> Result<()> {
        let weights = HashMap::from([
            ("weight".into(), Tensor::new(&[[1f32,2.,3.],[4.,5.,6.]], &Device::Cpu)?),
            ("bias".into(), Tensor::zeros(3,candle_core::DType::F32,&Device::Cpu)?)]);
        let layer = conv1d(2,3,VarBuilder::from_tensors(weights,candle_core::DType::F32,&Device::Cpu))?;
        assert_eq!(layer.forward(&Tensor::new(&[[1f32,2.]],&Device::Cpu)?)?.to_vec2::<f32>()?, vec![vec![9.,12.,15.]]);
        Ok(())
    }
    #[test]
    fn missing_weights_and_invalid_attention_are_rejected() {
        let (mut config,_) = fixture();
        config["n_head"] = 3.into();
        assert!(Gpt2::load(&config,VarBuilder::zeros(candle_core::DType::F32,&Device::Cpu),16).is_err());
        let (config,_) = fixture();
        assert!(Gpt2::load(&config,VarBuilder::from_tensors(HashMap::new(),candle_core::DType::F32,&Device::Cpu),16).is_err());
    }
}
