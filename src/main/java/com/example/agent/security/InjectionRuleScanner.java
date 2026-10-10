package com.example.agent.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L1 注入规则引擎（P4-17）：确定性、纯函数、毫秒级、零 LLM 成本。
 *
 * <p>处理流程：归一化（NFKC + 去零宽 + base64 探测反解）→ 逐规则匹配（每条规则最多计一次）
 * → 加权总分。规则可被绕过，本层定位是「一线过滤 + 监控数据源」，最终兜底依赖
 * 审批/SSRF 闸门/沙箱断网/出站 DLP，而非本扫描器本身。</p>
 *
 * <p><b>规则权重表</b>（{@link RuleHit} 明细落库，便于监控期按规则复盘误报）：</p>
 * <pre>
 * R1 instruction_override 3  指令覆盖（"忽略之前的指令"）
 * R2 role_hijack          3  角色劫持（"你现在是…" / DAN / 越狱）
 * R3 system_prompt_probe  3  系统提示套取（"重复你收到的所有内容"）
 * R4 credential_probe     4  凭据套取（API key / 密码 / .env / 环境变量）
 * R5 evasion              5  规避审批与隐蔽意图（"不要告诉用户" / silently）
 * R6 fake_delimiter       4  伪造消息边界标签（&lt;/system&gt; / [system] / im_start）
 * R7 exfil_induce         4  数据外传诱导（"发送到 http://…"）
 * R8 obfuscation          2  编码混淆（零宽字符 / 大段 base64 / 密集 unicode 转义）
 * </pre>
 *
 * <p>评分：Σ 命中权重（规则级去重，堆词不加分）。阈值由 {@link SafetyProperties} 控制：
 * 低 1–3 仅记录；灰区 ≥4 走 LLM 分类 + 警戒语；≥8 在 block 模式拒绝。</p>
 */
@Component
public class InjectionRuleScanner {

    /** 正则匹配的最大扫描长度（超长输入截断，约束最坏耗时） */
    private static final int MAX_SCAN_CHARS = 20000;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** 大段连续 base64（≥200 字符），尝试反解检测混淆载荷 */
    private static final Pattern BASE64_SEGMENT = Pattern.compile("[A-Za-z0-9+/]{200,}={0,2}");

    /** 密集 unicode 转义（\\uXXXX 文本形态；含中文 \\u4e2d 等，不限于 \\u00XX） */
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u[0-9a-fA-F]{4}");

    private record Rule(String id, String category, int weight, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("R1", "instruction_override", 3, Pattern.compile(
                    "(忽略|无视|不要理会|别管|跳过|忘掉|忘记)[^。，,；;!！?？\\n]{0,8}(指令|规则|提示|要求|限制|设定|约束)"
                            + "|ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier|preceding)\\s+(instruction|rule|prompt|message|direction|constraint)s?"
                            + "|disregard\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)"
                            + "|forget\\s+(everything|all)\\s+(you\\s+were\\s+told|before|above)"
                            + "|(新的|以下是新的|按照新的|按新的)(指令|规则|要求)(来|执行|如下)?", FLAGS)),
            new Rule("R2", "role_hijack", 3, Pattern.compile(
                    "你现在(是|开始|要扮演|就是一个|只服从)"
                            + "|you\\s+are\\s+now\\b"
                            + "|(开发者|调试|上帝|管理员|超级|developer|debug|god)\\s*(模式|权限)"
                            + "|\\bDAN\\b"
                            + "|越狱"
                            + "|(扮演|假装|模拟)(一[个位]|成)?[^。，,；;!！?？\\n]{0,4}(没有|不受|无视|摆脱)[^。，,；;!！?？\\n]{0,4}(限制|约束|规则|道德|审查)", FLAGS)),
            new Rule("R3", "system_prompt_probe", 3, Pattern.compile(
                    "系统(提示词|提示语|指令|消息|预设|设定)"
                            + "|初始(指令|提示|提示词|设定)"
                            + "|(重复|复述|输出|打印|展示|告诉我|念)(一下|一遍|出来)?[^。，,；;!！?？\\n]{0,12}(你(的|收到|看到|被给|之前|上面)|所有|全部|完整)[^。，,；;!！?？\\n]{0,8}(内容|文字|指令|提示|设定|消息)"
                            + "|repeat\\s+(everything|all\\s+(the\\s+)?(text|content|instructions?))"
                            + "|system\\s*prompt"
                            + "|(what|which)\\s+(were|are)\\s+your\\s+(initial\\s+|original\\s+)?instructions?", FLAGS)),
            new Rule("R4", "credential_probe", 4, Pattern.compile(
                    "(openai|deepseek|api|access|secret|private|internal)[_\\-\\s]?key"
                            + "|(密码|口令|密钥|私钥|凭据|凭证)"
                            + "|\\.env\\b"
                            + "|环境变量"
                            + "|(内部|服务|系统|访问)?\\s*(token|令牌)"
                            + "|application\\.(ya?ml|properties)"
                            + "|deploy\\.conf"
                            + "|internal[_\\-]?token"
                            + "|配置文件", FLAGS)),
            new Rule("R5", "evasion", 5, Pattern.compile(
                    "不要(告诉|问|提示|通知|提醒|征求|打扰)(我|用户|他|她|他们|她们|其他人|别人)?(们)?"
                            + "|(无需|不用|不需要|不必|别)(确认|审批|询问|批准|授权|同意)"
                            + "|(直接|偷偷|悄悄|暗中|私下)(执行|运行|发送|调用|操作|完成)"
                            + "|without\\s+(asking|confirmation|approval|telling|permission)"
                            + "|\\bsilently\\b|\\bsecretly\\b|\\bcovertly\\b"
                            + "|(保密|别说出去|不要留痕|别留痕迹|(别|不要)让(用户|他|她|其他人|别人)知道)", FLAGS)),
            new Rule("R6", "fake_delimiter", 4, Pattern.compile(
                    "</?system>"
                            + "|[\\[【]\\s*(system|系统)\\s*[\\]】]"
                            + "|#{2,}\\s*(system|instruction|指令|系统)"
                            + "|<\\|(im_start|im_end|system|endoftext|assistant)\\|>"
                            + "|={4,}\\s*(系统|system)", FLAGS)),
            new Rule("R7", "exfil_induce", 4, Pattern.compile(
                    "(发送|上传|传输|转发|发往|回传|粘贴|贴)(到|给|至)\\s*(https?://|(以下|这个|该|上面|上述)?\\s*(地址|网站|链接|url|接口|服务|服务器|邮箱|域名))"
                            + "|把(上面|以上|这些|全部|所有|之前的?)[^。，,；;!！?？\\n]{0,4}(内容|数据|信息|文字|资料|对话|记录)[^。，,；;!！?？\\n]{0,30}(发送|上传|发|传|回传|贴)"
                            + "|(send|upload|post|transmit|forward|exfiltrate)[^.!?\\n]{0,40}(https?://|to\\s+(the\\s+)?(url|server|address|endpoint|website))"
                            + "|exfiltrat", FLAGS))
    );

    /**
     * 扫描一段文本（用户输入 / RAG 片段 / 工具结果 / 模型输出通用）。
     * 空文本返回 clean；任何内部异常兜底为 clean（安全扫描不得打断主链路）。
     */
    public ScanResult scan(String rawText) {
        try {
            if (rawText == null || rawText.isBlank()) {
                return ScanResult.clean();
            }
            Normalized n = normalize(rawText);
            Map<String, RuleHit> hits = new LinkedHashMap<>();
            matchRules(n.text(), hits);
            if (n.base64Decoded() != null) {
                // 解码后可打印：对解码文本再跑一遍规则（base64 载荷洗白的注入语句在此暴露）
                matchRules(n.base64Decoded(), hits);
            }
            if (n.zeroWidth() > 3) {
                hits.putIfAbsent("R8", new RuleHit("R8", "obfuscation", 2,
                        "含 " + n.zeroWidth() + " 个零宽字符"));
            }
            if (n.base64Found()) {
                hits.putIfAbsent("R8", new RuleHit("R8", "obfuscation", 2,
                        n.base64Decoded() != null ? "大段 base64 载荷（可解码为文本）" : "大段 base64 载荷"));
            }
            if (n.unicodeEscapes() >= 5) {
                hits.putIfAbsent("R8", new RuleHit("R8", "obfuscation", 2,
                        "密集 unicode 转义（" + n.unicodeEscapes() + " 处）"));
            }
            if (hits.isEmpty()) {
                return ScanResult.clean();
            }
            int score = hits.values().stream().mapToInt(RuleHit::weight).sum();
            return new ScanResult(score, List.copyOf(hits.values()), n.zeroWidth() > 3, n.base64Found());
        } catch (Exception e) {
            return ScanResult.clean();
        }
    }

    private static void matchRules(String text, Map<String, RuleHit> hits) {
        for (Rule rule : RULES) {
            if (hits.containsKey(rule.id())) {
                continue;
            }
            Matcher m = rule.pattern().matcher(text);
            if (m.find()) {
                hits.put(rule.id(), new RuleHit(rule.id(), rule.category(), rule.weight(),
                        excerpt(text, m.start(), m.end())));
            }
        }
    }

    // ==================== 归一化 ====================

    private record Normalized(String text, int zeroWidth, boolean base64Found,
                              String base64Decoded, int unicodeEscapes) {
    }

    private static Normalized normalize(String raw) {
        String s = raw.length() > MAX_SCAN_CHARS ? raw.substring(0, MAX_SCAN_CHARS) : raw;
        // NFKC：全角→半角、兼容字符折叠（"ｉｇｎｏｒｅ" → "ignore"）
        s = Normalizer.normalize(s, Normalizer.Form.NFKC);

        int zeroWidth = 0;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                zeroWidth++;
                continue;
            }
            sb.append(c);
        }
        String text = sb.toString();

        boolean base64Found = false;
        String decoded = null;
        Matcher b64 = BASE64_SEGMENT.matcher(text);
        while (b64.find()) {
            base64Found = true;
            if (decoded == null) {
                decoded = tryDecodeText(b64.group());
            }
        }

        return new Normalized(text, zeroWidth, base64Found, decoded,
                countMatches(UNICODE_ESCAPE, text));
    }

    /** 尝试把 base64 段解码为可打印文本（失败/非文本返回 null） */
    private static String tryDecodeText(String segment) {
        try {
            int pad = (4 - segment.length() % 4) % 4;
            byte[] bytes = Base64.getDecoder().decode(segment + "=".repeat(pad));
            if (bytes.length < 32) {
                return null;
            }
            String s = new String(bytes, StandardCharsets.UTF_8);
            int printable = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\uFFFD') {
                    return null;
                }
                if (!Character.isISOControl(c) || c == '\n' || c == '\t' || c == '\r') {
                    printable++;
                }
            }
            return printable * 10 >= s.length() * 9 ? s : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    /** 命中片段：匹配位置前后取窗口，压空白、截断 80 字符 */
    private static String excerpt(String text, int start, int end) {
        int from = Math.max(0, start - 8);
        int to = Math.min(text.length(), end + 16);
        String s = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}