package com.example.agent.context;

/**
 * 轻量 token 估算器（P1-D 上下文预算）。
 *
 * 平台没有引入与模型匹配的 tokenizer（DeepSeek 未提供公开分词器，引入 BPE 词表会显著增大包体），
 * 预算控制只需要「相对准确、稳定、零依赖」的估算，因此按字符类别加权：
 * <ul>
 *   <li>CJK（汉字/假名/谚文/CJK 标点/全角字符）：约 1 token / 字符；</li>
 *   <li>ASCII：约 4 字符 / token（0.25）；</li>
 *   <li>空白：约 6 字符 / token；</li>
 *   <li>其他非 ASCII（emoji、西里尔等）：约 0.6 token / 字符。</li>
 * </ul>
 * 估算值用于预算分配与面板展示，不作为计费依据（计费以 LLM 返回 usage 为准）。
 */
public final class TokenEstimator {

    /** 单条消息的角色/分隔符固定开销 */
    public static final int MESSAGE_OVERHEAD = 4;

    /**
     * 单张图片的保守估算。平台以 AUTO detail 传入图片，上游按瓦片计费；
     * 具体 token 数随分辨率变化，这里取常见截图的量级，仅用于预算占位（避免图片回合预算虚高）。
     */
    public static final int IMAGE_TOKENS = 768;

    private TokenEstimator() {
    }

    /** 估算纯文本 token 数（null/空串为 0） */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                tokens += 0.15;
            } else if (isCjk(c)) {
                tokens += 1.0;
            } else if (c < 128) {
                tokens += 0.25;
            } else {
                tokens += 0.6;
            }
        }
        return (int) Math.ceil(tokens);
    }

    /** 估算一条消息（文本 + 角色/分隔符开销） */
    public static int estimateMessage(String text) {
        return estimate(text) + MESSAGE_OVERHEAD;
    }

    /**
     * 把文本裁剪到不超过 {@code maxTokens}（含裁剪标记本身的开销）。
     * 只在预算极紧时使用；裁剪标记明确告知模型/user 内容不完整。
     *
     * @param maxTokens 该段可用的最大 token 数（≤0 返回空串）
     */
    public static String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        if (estimate(text) <= maxTokens) {
            return text;
        }
        String marker = "…（已按上下文预算裁剪）";
        int markerTokens = estimate(marker);
        if (maxTokens <= markerTokens) {
            return "";
        }
        int budget = maxTokens - markerTokens;
        StringBuilder sb = new StringBuilder();
        double used = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            double cost = Character.isWhitespace(c) ? 0.15 : isCjk(c) ? 1.0 : c < 128 ? 0.25 : 0.6;
            if (used + cost > budget) {
                break;
            }
            sb.append(c);
            used += cost;
        }
        return sb.isEmpty() ? "" : sb + marker;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        if (script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL) {
            return true;
        }
        // CJK 标点（0x3000-0x303F）与全角字符（0xFF00-0xFFEF）：按 1 token/字符计
        return (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
