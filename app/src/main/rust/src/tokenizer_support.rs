use anyhow::{anyhow, Result};
use std::path::Path;
use tokenizers::{Tokenizer, AddedToken, models::bpe::BPE};

pub fn load(root: &Path, clip: bool) -> Result<Tokenizer> {
    let json = root.join("tokenizer.json");
    if json.is_file() {
        let mut tokenizer = Tokenizer::from_file(json).map_err(|e| anyhow!(e.to_string()))?;
        // Inference owns context limits and CLIP padding; serialized training settings must not trim prompts.
        tokenizer.with_padding(None);
        tokenizer.with_truncation(None).map_err(|e| anyhow!(e.to_string()))?;
        return Ok(tokenizer);
    }
    let vocab = root.join("vocab.json");
    let merges = root.join("merges.txt");
    let mut builder = BPE::from_file(&vocab.to_string_lossy(), &merges.to_string_lossy())
        .unk_token("<|endoftext|>".into());
    if clip { builder = builder.end_of_word_suffix("</w>".into()); }
    let mut tokenizer = Tokenizer::new(builder.build().map_err(|e| anyhow!(e.to_string()))?);
    if clip {
        use tokenizers::normalizers::{Sequence as Normalizers, NFC, Lowercase, Replace, Strip};
        use tokenizers::pre_tokenizers::{sequence::Sequence, whitespace::WhitespaceSplit, split::Split, byte_level::ByteLevel};
        use tokenizers::processors::template::TemplateProcessing;
        tokenizer.with_normalizer(Some(Normalizers::new(vec![NFC.into(), Lowercase.into(),
            Replace::new(tokenizers::normalizers::replace::ReplacePattern::Regex("\\s+".into()), " ").map_err(|e| anyhow!(e.to_string()))?.into(), Strip::new(true, true).into()])));
        let pattern = tokenizers::pre_tokenizers::split::SplitPattern::Regex("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+".into());
        tokenizer.with_pre_tokenizer(Some(Sequence::new(vec![WhitespaceSplit.into(),
            Split::new(pattern, tokenizers::SplitDelimiterBehavior::Isolated, false).map_err(|e| anyhow!(e.to_string()))?.into(),
            ByteLevel::new(false, false, false).into()])));
        let start = "<|startoftext|>";
        let end = "<|endoftext|>";
        let start_id = tokenizer.token_to_id(start).ok_or_else(|| anyhow!("Missing CLIP start token"))?;
        let end_id = tokenizer.token_to_id(end).ok_or_else(|| anyhow!("Missing CLIP end token"))?;
        tokenizer.add_special_tokens(&[AddedToken::from(start, true), AddedToken::from(end, true)]);
        tokenizer.with_post_processor(Some(TemplateProcessing::builder().try_single(format!("{start} $A {end}")).map_err(|e| anyhow!(e.to_string()))?
            .special_tokens(vec![(start, start_id), (end, end_id)]).build().map_err(|e| anyhow!(e.to_string()))?));
    } else {
        tokenizer.with_pre_tokenizer(Some(tokenizers::pre_tokenizers::byte_level::ByteLevel::new(false, true, true)));
        tokenizer.with_decoder(Some(tokenizers::decoders::byte_level::ByteLevel::default()));
    }
    Ok(tokenizer)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn training_padding_and_truncation_do_not_change_inference_tokens() -> Result<()> {
        use tokenizers::{models::wordlevel::WordLevel, PaddingParams, PaddingStrategy, TruncationParams};
        let model = WordLevel::builder().vocab([("a".to_owned(), 0), ("[UNK]".to_owned(), 1)].into_iter().collect())
            .unk_token("[UNK]".into()).build().map_err(|e| anyhow!(e.to_string()))?;
        let mut tokenizer = Tokenizer::new(model);
        tokenizer.with_pre_tokenizer(Some(tokenizers::pre_tokenizers::whitespace::WhitespaceSplit));
        tokenizer.with_padding(Some(PaddingParams { strategy: PaddingStrategy::Fixed(8), ..Default::default() }));
        tokenizer.with_truncation(Some(TruncationParams { max_length: 3, ..Default::default() })).map_err(|e| anyhow!(e.to_string()))?;
        let root = std::env::temp_dir().join(format!("solus-tokenizer-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_nanos()));
        std::fs::create_dir(&root)?;
        tokenizer.save(root.join("tokenizer.json"), false).map_err(|e| anyhow!(e.to_string()))?;
        let loaded = load(&root, false)?;
        let result = loaded.encode("a a a a", false).map_err(|e| anyhow!(e.to_string()))?;
        std::fs::remove_dir_all(root)?;
        assert_eq!(result.get_ids(), &[0, 0, 0, 0]);
        Ok(())
    }
}
