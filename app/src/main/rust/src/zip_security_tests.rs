use candle_core::{Device, Tensor};
use std::sync::atomic::{AtomicU64, Ordering};

static COUNTER: AtomicU64 = AtomicU64::new(0);

struct Archive(std::path::PathBuf);
impl Archive {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "solus-zip-security-{}-{}.npz", std::process::id(), COUNTER.fetch_add(1, Ordering::Relaxed)
        ));
        let tensor = Tensor::new(&[1f32, 2f32, 3f32], &Device::Cpu).unwrap();
        Tensor::write_npz(&[("tensor", &tensor)], &path).unwrap();
        Self(path)
    }
}
impl Drop for Archive {
    fn drop(&mut self) { let _ = std::fs::remove_file(&self.0); }
}

#[test]
fn zip_security_valid_npz_preserves_tensor_values() {
    let archive = Archive::new();
    let tensors = Tensor::read_npz(&archive.0).unwrap();
    assert_eq!(tensors[0].0, "tensor");
    assert_eq!(tensors[0].1.to_vec1::<f32>().unwrap(), vec![1f32, 2f32, 3f32]);
}

#[test]
fn zip_security_rejects_mismatched_local_and_directory_names() {
    let archive = Archive::new();
    let mut bytes = std::fs::read(&archive.0).unwrap();
    assert_eq!(&bytes[..4], b"PK\x03\x04");
    // Change only the local filename. The central directory still says tensor.npy.
    bytes[30] = b'x';
    std::fs::write(&archive.0, bytes).unwrap();
    assert!(Tensor::read_npz(&archive.0).is_err());
    assert!(candle_core::npy::NpzTensors::new(&archive.0).is_err());
}
