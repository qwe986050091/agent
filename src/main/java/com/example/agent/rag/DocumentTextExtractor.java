package com.example.agent.rag;

import jakarta.annotation.PreDestroy;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 二进制文档正文抽取（P1-9.2）。
 *
 * 基于 Apache Tika 自动探测格式，支持 PDF / Word(doc/docx) / Excel(xls/xlsx) /
 * PPT(ppt/pptx) / HTML 等。安全与资源边界：
 *  - 正文字符上限：{@code rag.parse.max-text-chars}，超出直接判定失败（防解析炸弹撑爆内存）
 *  - 解析墙钟超时：{@code rag.parse.timeout-seconds}，超时中断解析任务（防恶意/损坏文件挂死）
 *  - 有界解析线程池：{@code rag.parse.threads}，满载 AbortPolicy 快速失败
 *
 * 说明：扫描件/图片型 PDF 无文本层时抽取出空文本，按"无可提取文本"失败处理（v1 不做 OCR）。
 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    private final int maxTextChars;
    private final long timeoutSeconds;
    private final ExecutorService parseExecutor;

    public DocumentTextExtractor(
            @Value("${rag.parse.max-text-chars:500000}") int maxTextChars,
            @Value("${rag.parse.timeout-seconds:60}") long timeoutSeconds,
            @Value("${rag.parse.threads:2}") int threads) {
        this.maxTextChars = maxTextChars;
        this.timeoutSeconds = timeoutSeconds;
        AtomicInteger seq = new AtomicInteger();
        int pool = Math.max(1, threads);
        // 有界队列：同时在解析的大文件数量受控；满载由调用方返回 503 友好提示
        this.parseExecutor = new ThreadPoolExecutor(
                pool, pool, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r, "rag-doc-parser-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 同步抽取文档正文（内部提交到解析线程池并限时等待）。
     *
     * @throws DocumentParseException 文件不受支持/损坏/加密/正文为空/超限时抛出，消息可直接回给用户
     * @throws ParseBusyException     解析线程池满载时抛出，调用方应返回 503
     */
    public String extract(String fileName, byte[] content) {
        Future<String> future;
        try {
            future = parseExecutor.submit(() -> doExtract(fileName, content));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new ParseBusyException("文档解析服务繁忙，请稍后重试");
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[RAG] 文档《{}》解析超过 {}s，已中止", fileName, timeoutSeconds);
            throw new DocumentParseException("文档解析超时（>" + timeoutSeconds + "s），请检查文件是否过大或损坏");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DocumentParseException("文档解析被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DocumentParseException dpe) {
                throw dpe;
            }
            throw new DocumentParseException("文档解析失败: " + cause.getMessage(), cause);
        }
    }

    private String doExtract(String fileName, byte[] content) throws InterruptedException {
        // writeLimit：达到上限后 Tika 抛 SAXException，天然防解析炸弹
        BodyContentHandler handler = new BodyContentHandler(maxTextChars);
        Metadata tikaMetadata = new Metadata();
        if (fileName != null && !fileName.isBlank()) {
            tikaMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        }
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();
        // 显式回填：内嵌文档递归解析时仍走同一 Parser（Tika 标准用法）
        context.set(Parser.class, parser);

        try (InputStream in = new ByteArrayInputStream(content)) {
            parser.parse(in, handler, tikaMetadata, context);
        } catch (SAXException e) {
            if (isWriteLimitReached(e)) {
                throw new DocumentParseException(
                        "文档正文超过 " + maxTextChars + " 字符上限，请精简后重新上传");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            throw new DocumentParseException("文档解析失败（文件可能已损坏）: " + rootMessage(e));
        } catch (TikaException e) {
            // 加密 PDF / 不支持的格式 / 损坏文件统一收敛
            throw new DocumentParseException("无法解析该文档（可能已加密或格式不受支持）: " + rootMessage(e));
        } catch (IOException e) {
            throw new DocumentParseException("文档读取失败: " + rootMessage(e));
        }

        String text = normalize(handler.toString());
        if (text.isBlank()) {
            throw new DocumentParseException(
                    "未从文档中提取到有效文本（扫描件/图片型 PDF 暂不支持，请上传带文本层的文档）");
        }
        return text;
    }

    /** Tika 达到 BodyContentHandler 字符上限时抛 SAXException，消息含 "contained more than" */
    private boolean isWriteLimitReached(Throwable e) {
        String message = e.getMessage();
        return message != null && message.contains("contained more than");
    }

    /** 压缩 Tika 抽出文本中的多余空白（PDF 抽取常带大量空行/缩进），但保留单换行可读性 */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\u0000', ' ');
        // 行尾去空白，3 个以上连续换行压成 2 个
        text = text.replaceAll("[\\t\\x0B\\f\\r ]+\\n", "\n");
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.replaceAll("[\\t\\x0B\\f ]{2,}", " ");
        return text.trim();
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }

    @PreDestroy
    public void shutdown() {
        parseExecutor.shutdownNow();
    }

    /** 文档内容解析失败（不受支持/加密/损坏/空文本/超限/超时），映射为 422 */
    public static class DocumentParseException extends RuntimeException {
        public DocumentParseException(String message) {
            super(message);
        }

        public DocumentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 解析线程池满载，映射为 503 */
    public static class ParseBusyException extends RuntimeException {
        public ParseBusyException(String message) {
            super(message);
        }
    }
}
