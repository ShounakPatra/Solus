package com.shounak.localmeshai.ai;

import java.util.*;

public final class AiCoreLogic {
    private AiCoreLogic() {}

    // ==========================================
    // 1. Logits & FP16 / BF16 conversions
    // ==========================================

    public static double halfToFloat(int bits) {
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 31;
        int mantissa = bits & 1023;
        float f;
        if (exponent == 0) {
            f = ((bits & 0x8000) != 0 ? -1f : 1f) * mantissa * (1f / 16777216f);
        } else if (exponent == 31) {
            f = Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
        } else {
            f = Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
        }
        return (double) f;
    }

    public static double bfloat16ToFloat(int bits) {
        float f = Float.intBitsToFloat((bits & 0xffff) << 16);
        return (double) f;
    }

    public static double[] convertShortsToDoubles(int[] shorts, boolean isBfloat16) {
        double[] out = new double[shorts.length];
        for (int i = 0; i < shorts.length; i++) {
            int bits = shorts[i] & 0xffff;
            out[i] = isBfloat16 ? bfloat16ToFloat(bits) : halfToFloat(bits);
        }
        return out;
    }

    // ==========================================
    // 2. Document Preparation string helpers
    // ==========================================

    public static String sanitizeExtension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = (dot >= 0) ? name.substring(dot + 1) : "bin";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                if (sb.length() == 12) break;
            }
        }
        return sb.toString();
    }

    public static String fileIdentity(String path, long length, long lastModified) {
        return path + "|" + length + "|" + lastModified;
    }

    // ==========================================
    // 3. Portable Prompt formatting
    // ==========================================

    public static List<String> stopSequences(String formatOrTemplate) {
        List<String> list = new ArrayList<>();
        if ("chatml".equals(formatOrTemplate) || formatOrTemplate.contains("<|im_end|>")) {
            list.add("<|im_end|>");
        }
        if ("llama3".equals(formatOrTemplate) || formatOrTemplate.contains("<|eot_id|>")) {
            list.add("<|eot_id|>");
        }
        if ("gemma".equals(formatOrTemplate) || formatOrTemplate.contains("<end_of_turn>")) {
            list.add("<end_of_turn>");
        }
        if ("phi3".equals(formatOrTemplate) || formatOrTemplate.contains("<|end|>")) {
            list.add("<|end|>");
        }
        return list;
    }

    public static String detectFallbackFormat(String modelType, String solusFormat, List<String> specialTokens) {
        if (!solusFormat.trim().isEmpty()) return solusFormat;
        Set<String> tokenSet = new HashSet<>(specialTokens);
        if (tokenSet.contains("<|im_start|>") && tokenSet.contains("<|im_end|>")) return "chatml";
        if (tokenSet.contains("<|start_header_id|>") && tokenSet.contains("<|end_header_id|>") && tokenSet.contains("<|eot_id|>")) return "llama3";
        switch (modelType) {
            case "qwen2": return "chatml";
            case "gpt2": return "completion";
            case "llama": return "llama2";
            case "mistral": return "mistral";
            case "gemma": return "gemma";
            case "phi3": return "phi3";
            case "phi": return "phi";
            default: return "plain";
        }
    }

    public static String renderPrompt(String format, List<String> roles, List<String> texts) {
        int n = Math.min(roles.size(), texts.size());
        for (int i = 0; i < n; i++) {
            String r = roles.get(i);
            if (!"system".equals(r) && !"user".equals(r) && !"assistant".equals(r)) {
                throw new IllegalArgumentException("Invalid role: " + r);
            }
        }
        switch (format) {
            case "completion": {
                StringJoiner sj = new StringJoiner("\n\n");
                for (int i = 0; i < n; i++) sj.add(texts.get(i));
                return sj.toString();
            }
            case "plain": {
                StringJoiner sj = new StringJoiner("\n\n");
                for (int i = 0; i < n; i++) sj.add(roles.get(i) + ": " + texts.get(i));
                return sj.toString() + "\n\nassistant:";
            }
            case "chatml": {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    sb.append("<|im_start|>").append(roles.get(i)).append("\n")
                      .append(texts.get(i)).append("<|im_end|>\n");
                }
                sb.append("<|im_start|>assistant\n");
                return sb.toString();
            }
            case "llama3": {
                StringBuilder sb = new StringBuilder("<|begin_of_text|>");
                for (int i = 0; i < n; i++) {
                    sb.append("<|start_header_id|>").append(roles.get(i)).append("<|end_header_id|>\n\n")
                      .append(texts.get(i)).append("<|eot_id|>");
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
                return sb.toString();
            }
            case "phi3": {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    sb.append("<|").append(roles.get(i)).append("|>\n")
                      .append(texts.get(i)).append("<|end|>\n");
                }
                sb.append("<|assistant|>\n");
                return sb.toString();
            }
            case "gemma": {
                StringJoiner sj = new StringJoiner("\n\n");
                for (int i = 0; i < n; i++) {
                    if ("system".equals(roles.get(i))) sj.add(texts.get(i));
                }
                String system = sj.toString();
                StringBuilder sb = new StringBuilder("<bos>");
                boolean first = true;
                for (int i = 0; i < n; i++) {
                    String role = roles.get(i);
                    if ("system".equals(role)) continue;
                    String text = texts.get(i);
                    String content = (first && "user".equals(role) && !system.trim().isEmpty())
                            ? system + "\n\n" + text : text;
                    first = false;
                    sb.append("<start_of_turn>")
                      .append("assistant".equals(role) ? "model" : role)
                      .append("\n").append(content).append("<end_of_turn>\n");
                }
                sb.append("<start_of_turn>model\n");
                return sb.toString();
            }
            case "llama2":
            case "mistral": {
                StringJoiner sj = new StringJoiner("\n\n");
                for (int i = 0; i < n; i++) {
                    if ("system".equals(roles.get(i))) sj.add(texts.get(i));
                }
                String system = sj.toString();
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (int i = 0; i < n; i++) {
                    String role = roles.get(i);
                    if ("system".equals(role)) continue;
                    String text = texts.get(i);
                    if ("user".equals(role)) {
                        if (first || "llama2".equals(format)) sb.append("<s>");
                        sb.append("[INST] ");
                        if (first && !system.trim().isEmpty()) {
                            if ("llama2".equals(format)) {
                                sb.append("<<SYS>>\n").append(system).append("\n<</SYS>>\n\n");
                            } else {
                                sb.append(system).append("\n\n");
                            }
                        }
                        sb.append(text).append(" [/INST]");
                        first = false;
                    } else {
                        sb.append(' ').append(text).append("</s>");
                    }
                }
                return sb.toString();
            }
            case "phi": {
                StringJoiner sj = new StringJoiner("\n");
                for (int i = 0; i < n; i++) {
                    String role = roles.get(i);
                    sj.add(("assistant".equals(role) ? "Output" : "Instruct") + ": " + texts.get(i));
                }
                return sj.toString() + "\nOutput:";
            }
            default:
                throw new IllegalArgumentException("Unsupported prompt format " + format);
        }
    }

    // ==========================================
    // 4. History budgeting
    // ==========================================

    public static int[] dropOldestTurnIndices(List<String> roles) {
        List<Integer> users = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            if ("user".equals(roles.get(i))) users.add(i);
        }
        if (users.size() < 2) return new int[0];
        int dropStart = users.get(0);
        int dropEnd = users.get(1);
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++) {
            if ("system".equals(roles.get(i)) || i < dropStart || i >= dropEnd) {
                keep.add(i);
            }
        }
        int[] result = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) result[i] = keep.get(i);
        return result;
    }

    // ==========================================
    // 5. Response finalizer helpers
    // ==========================================

    public static String effectiveAnswer(String rawAnswer, String fallbackAnswer) {
        String trimmed = rawAnswer.trim();
        return !trimmed.isEmpty() ? trimmed : fallbackAnswer.trim();
    }

    public static String effectiveReasoning(String rawReasoning, String fallbackReasoning) {
        String trimmed = rawReasoning.trim();
        return !trimmed.isEmpty() ? trimmed : fallbackReasoning.trim();
    }

    public static int finalizeStatus(
        String effectiveAnswer,
        String effectiveReasoning,
        boolean isStopped,
        boolean hasError
    ) {
        if (isStopped) return 1; // Stopped
        if (hasError) return 2;  // FailedWithError
        if (!effectiveAnswer.trim().isEmpty()) return 0; // Completed
        if (!effectiveReasoning.trim().isEmpty()) return 3; // FailedReasoningOnly
        return 4; // FailedNoAnswer
    }
}
