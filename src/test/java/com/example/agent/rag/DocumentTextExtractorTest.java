package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentTextExtractor} 单测（P1-9.2）：
 * HTML 抽取、最小 docx(OOXML zip) 抽取、损坏文件/空文件拒绝、正文上限拒绝。
 */
class DocumentTextExtractorTest {

    private final DocumentTextExtractor extractor = new DocumentTextExtractor(500_000, 60, 2);

    @Test
    void extractsHtmlAndStripsTags() {
        byte[] html = """
                <html><head><title>政策</title></head>
                <body><h1>退款政策</h1><p>Q3 订单支持七天无理由退款，编号 REF-2026-0001。</p></body></html>
                """.getBytes(StandardCharsets.UTF_8);

        String text = extractor.extract("policy.html", html);

        assertTrue(text.contains("退款政策"), "应抽到标题文本: " + text);
        assertTrue(text.contains("REF-2026-0001"), "应保留编号原文: " + text);
        assertTrue(!text.contains("<"), "不应残留 HTML 标签: " + text);
    }

    @Test
    void extractsMinimalDocx() throws Exception {
        byte[] docx = minimalDocx("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>知识库 Word 文档内容 ABC-123</w:t></w:r></w:p></w:body>
                </w:document>""");

        String text = extractor.extract("demo.docx", docx);

        assertTrue(text.contains("知识库 Word 文档内容 ABC-123"), "应从 OOXML 中抽到正文: " + text);
    }

    @Test
    void rejectsCorruptedPdf() {
        // %PDF 魔数让 Tika 选中 PDF 解析器，随后损坏结构解析失败
        // （纯随机文本会被 Tika 当 text/plain 兜底成功抽取，属预期行为）
        byte[] garbage = ("%PDF-1.4\r%\u00e2\u00e3\u00cf\u00d3\r" + "garbage-structure-".repeat(40))
                .getBytes(StandardCharsets.ISO_8859_1);

        DocumentTextExtractor.DocumentParseException ex = assertThrows(
                DocumentTextExtractor.DocumentParseException.class,
                () -> extractor.extract("broken.pdf", garbage));
        // 消息应可直接展示给用户
        assertTrue(ex.getMessage().contains("解析") || ex.getMessage().contains("文本"),
                "异常消息应可读: " + ex.getMessage());
    }

    @Test
    void rejectsEmptyContent() {
        assertThrows(DocumentTextExtractor.DocumentParseException.class,
                () -> extractor.extract("empty.txt", new byte[0]));
    }

    @Test
    void rejectsWhenTextExceedsLimit() {
        // 上限仅 50 字符：长 HTML 必然超限
        DocumentTextExtractor small = new DocumentTextExtractor(50, 30, 1);
        byte[] html = ("<html><body>" + "内容重复".repeat(100) + "</body></html>")
                .getBytes(StandardCharsets.UTF_8);

        DocumentTextExtractor.DocumentParseException ex = assertThrows(
                DocumentTextExtractor.DocumentParseException.class,
                () -> small.extract("large.html", html));
        assertTrue(ex.getMessage().contains("上限"), "应提示超上限: " + ex.getMessage());
    }

    /** 用 zip 手工拼一个结构最小但合法的 .docx（Tika OOXML 解析器可识别） */
    private static byte[] minimalDocx(String documentXml) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            putEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>""");
            putEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>""");
            putEntry(zip, "word/document.xml", documentXml);
        }
        return bos.toByteArray();
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
