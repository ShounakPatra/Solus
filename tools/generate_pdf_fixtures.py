#!/usr/bin/env python3
"""
Generates PDF test fixtures for ReleaseOptimizationSmokeTest in app/src/androidTest/assets/pdf:
1. ordinary_text.pdf - Text with known expected sentence: "The quick brown fox jumps over the lazy dog."
2. multilingual_embedded.pdf - Multilingual text with standard encoding: "Hello world / Hola mundo / Bonjour le monde / 12345"
3. encrypted_empty_password.pdf - Encrypted PDF with empty user password ("")
4. password_protected.pdf - Encrypted PDF with user password "secret123"
5. image_only.pdf - Valid PDF with vector graphics but no text elements (triggers OCR fallback)
6. malformed.pdf - Corrupted PDF bytes
"""

import hashlib
from pathlib import Path
import struct

OUTPUT_DIR = Path("app/src/androidTest/assets/pdf")
PADDING = b"\x28\xbf\x4e\x5e\x4e\x75\x8a\x41\x64\x00\x4e\x56\xff\xfa\x01\x08\x2e\x2e\x00\xb6\xd0\x68\x3e\x80\x2f\x0c\xa9\xfe\x64\x53\x69\x7a"


def rc4(key: bytes, data: bytes) -> bytes:
    S = list(range(256))
    j = 0
    for i in range(256):
        j = (j + S[i] + key[i % len(key)]) % 256
        S[i], S[j] = S[j], S[i]
    i = j = 0
    res = bytearray()
    for b in data:
        i = (i + 1) % 256
        j = (j + S[i]) % 256
        S[i], S[j] = S[j], S[i]
        res.append(b ^ S[(S[i] + S[j]) % 256])
    return bytes(res)


def pad_password(pwd: str) -> bytes:
    p_bytes = pwd.encode("latin-1")
    if len(p_bytes) < 32:
        return p_bytes + PADDING[:32 - len(p_bytes)]
    return p_bytes[:32]


def compute_enc_key(user_pwd: str, o_val: bytes, p_val: int, id_val: bytes, key_len: int = 5) -> bytes:
    padded = pad_password(user_pwd)
    m = hashlib.md5()
    m.update(padded)
    m.update(o_val)
    m.update(struct.pack("<i", p_val))
    m.update(id_val)
    return m.digest()[:key_len]


def compute_o_value(owner_pwd: str, user_pwd: str, key_len: int = 5) -> bytes:
    padded_owner = pad_password(owner_pwd)
    m = hashlib.md5()
    m.update(padded_owner)
    owner_key = m.digest()[:key_len]
    padded_user = pad_password(user_pwd)
    return rc4(owner_key, padded_user)


def compute_u_value(enc_key: bytes) -> bytes:
    return rc4(enc_key, PADDING)


def encrypt_obj_data(data: bytes, enc_key: bytes, obj_num: int, gen_num: int = 0) -> bytes:
    m = hashlib.md5()
    m.update(enc_key)
    m.update(struct.pack("<I", obj_num)[:3])
    m.update(struct.pack("<I", gen_num)[:2])
    obj_key = m.digest()[:min(len(enc_key) + 5, 16)]
    return rc4(obj_key, data)


def escape_pdf_string(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def build_simple_pdf(stream_content: bytes, font_name: str = "Helvetica") -> bytes:
    body = bytearray()
    offsets = [0]

    def add_obj(obj_num: int, content: bytes):
        offsets.append(len(body))
        body.extend(f"{obj_num} 0 obj\n".encode("latin-1"))
        body.extend(content)
        body.extend(b"\nendobj\n")

    body.extend(b"%PDF-1.4\n")
    # 1: Catalog
    add_obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    # 2: Pages
    add_obj(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    # 3: Page
    add_obj(3, f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>".encode("latin-1"))
    # 4: Contents stream
    stream_obj = f"<< /Length {len(stream_content)} >>\nstream\n".encode("latin-1") + stream_content + b"\nendstream"
    add_obj(4, stream_obj)
    # 5: Font
    add_obj(5, f"<< /Type /Font /Subtype /Type1 /BaseFont /{font_name} >>".encode("latin-1"))

    xref_offset = len(body)
    body.extend(b"xref\n0 6\n0000000000 65535 f \n")
    for off in offsets[1:]:
        body.extend(f"{off:010d} 00000 n \n".encode("latin-1"))
    body.extend(f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("latin-1"))
    return bytes(body)


def build_encrypted_pdf(stream_content: bytes, user_pwd: str, owner_pwd: str = "masterpass") -> bytes:
    doc_id = b"1234567890abcdef"
    p_val = -64  # permissions
    key_len = 5  # 40-bit key for PDF 1.4 compatibility
    o_val = compute_o_value(owner_pwd, user_pwd, key_len)
    enc_key = compute_enc_key(user_pwd, o_val, p_val, doc_id, key_len)
    u_val = compute_u_value(enc_key)

    body = bytearray()
    offsets = [0]

    def add_obj(obj_num: int, content: bytes):
        offsets.append(len(body))
        body.extend(f"{obj_num} 0 obj\n".encode("latin-1"))
        body.extend(content)
        body.extend(b"\nendobj\n")

    body.extend(b"%PDF-1.4\n")
    # 1: Catalog
    add_obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
    # 2: Pages
    add_obj(2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    # 3: Page
    add_obj(3, b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")

    # Encrypt stream 4
    enc_stream = encrypt_obj_data(stream_content, enc_key, 4, 0)
    stream_obj = f"<< /Length {len(enc_stream)} >>\nstream\n".encode("latin-1") + enc_stream + b"\nendstream"
    add_obj(4, stream_obj)

    # 5: Font
    add_obj(5, b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    # 6: Encrypt dict
    o_hex = o_val.hex()
    u_hex = u_val.hex()
    enc_dict = f"<< /Filter /Standard /V 1 /R 2 /O <{o_hex}> /U <{u_hex}> /P {p_val} >>".encode("latin-1")
    add_obj(6, enc_dict)

    xref_offset = len(body)
    body.extend(b"xref\n0 7\n0000000000 65535 f \n")
    for off in offsets[1:]:
        body.extend(f"{off:010d} 00000 n \n".encode("latin-1"))
    id_hex = doc_id.hex()
    body.extend(f"trailer\n<< /Size 7 /Root 1 0 R /Encrypt 6 0 R /ID [<{id_hex}> <{id_hex}>] >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("latin-1"))
    return bytes(body)


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    # 1. ordinary_text.pdf
    text1 = "BT\n/F1 12 Tf\n72 712 Td\n(The quick brown fox jumps over the lazy dog.) Tj\nET\n".encode("latin-1")
    pdf1 = build_simple_pdf(text1)
    (OUTPUT_DIR / "ordinary_text.pdf").write_bytes(pdf1)
    print("Created ordinary_text.pdf")

    # 2. multilingual_embedded.pdf
    text2 = "BT\n/F1 12 Tf\n72 712 Td\n(Hello world / Hola mundo / Bonjour le monde / 12345) Tj\nET\n".encode("latin-1")
    pdf2 = build_simple_pdf(text2)
    (OUTPUT_DIR / "multilingual_embedded.pdf").write_bytes(pdf2)
    print("Created multilingual_embedded.pdf")

    # 3. encrypted_empty_password.pdf
    text3 = "BT\n/F1 12 Tf\n72 712 Td\n(This document is encrypted with an empty user password.) Tj\nET\n".encode("latin-1")
    pdf3 = build_encrypted_pdf(text3, user_pwd="")
    (OUTPUT_DIR / "encrypted_empty_password.pdf").write_bytes(pdf3)
    print("Created encrypted_empty_password.pdf")

    # 4. password_protected.pdf
    text4 = "BT\n/F1 12 Tf\n72 712 Td\n(Protected confidential content.) Tj\nET\n".encode("latin-1")
    pdf4 = build_encrypted_pdf(text4, user_pwd="secret123")
    (OUTPUT_DIR / "password_protected.pdf").write_bytes(pdf4)
    print("Created password_protected.pdf")

    # 5. image_only.pdf (vector drawing without text)
    graphics = b"100 100 200 200 re\nf\n"
    pdf5 = build_simple_pdf(graphics)
    (OUTPUT_DIR / "image_only.pdf").write_bytes(pdf5)
    print("Created image_only.pdf")

    # 6. malformed.pdf
    pdf6 = b"%PDF-1.4\nCorrupted content without xref table or trailer\n%%EOF\n"
    (OUTPUT_DIR / "malformed.pdf").write_bytes(pdf6)
    print("Created malformed.pdf")


if __name__ == "__main__":
    main()
