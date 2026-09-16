package com.example.agent.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧 InMemory 向量 JSON 快照 → PGVector 一次性迁移器（P1-9.1）。
 *
 * 触发条件（全部满足）：rag.pgvector.migrate-snapshot=true、PG 目标表行数为 0、快照文件存在。
 * 幂等：迁移成功后快照改名 .migrated-时间戳；PG 非空则跳过。任何失败只记 ERROR 不阻断启动
 * （PG 仍是可用空库；保留快照文件，修复后重启会重试）。
 *
 * InMemoryEmbeddingStore 没有公开的条目导出 API（Entry 包级私有），这里用一次性反射读取
 * id/向量/片段并按原 ID 写回 PG，避免重新 embedding（本地模型向量须与库内完全一致）。
 */
public class SnapshotMigrator {

    private static final Logger log = LoggerFactory.getLogger(SnapshotMigrator.class);

    /** 单批写入条数 */
    private static final int BATCH = 200;

    private final Path snapshotFile;
    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String table;

    public SnapshotMigrator(Path snapshotFile, String host, int port, String database,
                            String table, String user, String password) {
        this.snapshotFile = snapshotFile;
        this.table = table;
        this.user = user;
        this.password = password;
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public void migrate(EmbeddingStore<TextSegment> target) {
        if (!Files.exists(snapshotFile)) {
            log.info("[RAG-Migrate] 未发现旧向量快照 {}，无需迁移", snapshotFile);
            return;
        }
        try {
            if (pgRowCount() > 0) {
                log.info("[RAG-Migrate] PG 表 {} 已有数据，跳过快照迁移（旧快照保留）", table);
                return;
            }
        } catch (Exception e) {
            log.error("[RAG-Migrate] 检查 PG 行数失败，跳过迁移（旧快照保留）: {}", e.getMessage());
            return;
        }

        InMemoryEmbeddingStore<TextSegment> old;
        List<Entry> entries;
        try {
            old = InMemoryEmbeddingStore.fromFile(snapshotFile);
            entries = reflectEntries(old);
        } catch (Exception e) {
            log.error("[RAG-Migrate] 读取旧快照失败，跳过迁移（文件保留）: {}", e.getMessage());
            return;
        }
        if (entries.isEmpty()) {
            log.info("[RAG-Migrate] 旧快照为空，直接归档: {}", snapshotFile);
            archiveSnapshot();
            return;
        }

        log.info("[RAG-Migrate] 开始迁移 {} 个向量片段到 PG 表 {}", entries.size(), table);
        try {
            for (int from = 0; from < entries.size(); from += BATCH) {
                int to = Math.min(from + BATCH, entries.size());
                List<String> ids = new ArrayList<>(to - from);
                List<Embedding> embeddings = new ArrayList<>(to - from);
                List<TextSegment> segments = new ArrayList<>(to - from);
                for (int i = from; i < to; i++) {
                    Entry e = entries.get(i);
                    ids.add(e.id);
                    embeddings.add(e.embedding);
                    segments.add(e.segment);
                }
                // 按原 ID 写入：docId 删除/去重语义与旧库一致
                target.addAll(ids, embeddings, segments);
            }
            long after = pgRowCount();
            if (after != entries.size()) {
                log.error("[RAG-Migrate] 迁移后行数 {} 与快照条目数 {} 不一致，快照保留待人工核查",
                        after, entries.size());
                return;
            }
            log.info("[RAG-Migrate] 迁移完成：{} 个片段已入 PG", after);
            archiveSnapshot();
        } catch (Exception e) {
            log.error("[RAG-Migrate] 迁移失败（PG 可能已有部分数据，修复后请清空表 {} 再重启）: {}",
                    table, e);
        }
    }

    private long pgRowCount() throws Exception {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void archiveSnapshot() {
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Path archived = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".migrated-" + stamp);
            Files.move(snapshotFile, archived);
            log.info("[RAG-Migrate] 旧快照已归档为 {}", archived);
        } catch (Exception e) {
            log.warn("[RAG-Migrate] 快照归档失败（不影响迁移结果）: {}", e.getMessage());
        }
    }

    /**
     * 反射读取 InMemoryEmbeddingStore 的包级私有 entries 字段。
     * 包级可见：便于单测固定与当前 langchain4j 版本的反射契约。
     */
    @SuppressWarnings("unchecked")
    static List<Entry> reflectEntries(InMemoryEmbeddingStore<TextSegment> store) throws Exception {
        Field entriesField = InMemoryEmbeddingStore.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        List<Object> raw = (List<Object>) entriesField.get(store);

        Class<?> entryClass = Class.forName(
                "dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore$Entry");
        Field idField = entryClass.getDeclaredField("id");
        Field embeddingField = entryClass.getDeclaredField("embedding");
        Field embeddedField = entryClass.getDeclaredField("embedded");
        idField.setAccessible(true);
        embeddingField.setAccessible(true);
        embeddedField.setAccessible(true);

        List<Entry> result = new ArrayList<>(raw.size());
        for (Object o : raw) {
            result.add(new Entry(
                    (String) idField.get(o),
                    (Embedding) embeddingField.get(o),
                    (TextSegment) embeddedField.get(o)));
        }
        return result;
    }

    /** 迁移用的内部三元组（包级可见便于单测） */
    record Entry(String id, Embedding embedding, TextSegment segment) {
    }
}
