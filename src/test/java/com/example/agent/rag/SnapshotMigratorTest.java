package com.example.agent.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link SnapshotMigrator} 反射读取契约单测（P1-9.1）：
 * 固定对 langchain4j InMemoryEmbeddingStore 包级私有 entries 字段的反射假设，
 * 升级 langchain4j 后若内部结构变化，这里会第一时间失败。
 */
class SnapshotMigratorTest {

    @Test
    void reflectsEntriesWithIdVectorAndSegment() throws Exception {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        float[] vector = new float[512];
        vector[0] = 0.125f;
        vector[511] = -0.5f;
        TextSegment segment = TextSegment.from("迁移片段内容",
                new Metadata().put(RagService.META_USER_ID, "u1")
                        .put(RagService.META_TYPE, RagService.TYPE_DOCUMENT)
                        .put(RagService.META_DOC_ID, "doc-1")
                        .put(RagService.META_CHUNK_INDEX, 3));
        store.add("fixed-id-1", new Embedding(vector), segment);
        store.add(new Embedding(new float[512]),
                TextSegment.from("第二条", new Metadata().put(RagService.META_USER_ID, "u2")));

        SnapshotMigrator migrator = new SnapshotMigrator(
                Path.of("nonexistent-snapshot.json"), "127.0.0.1", 5432,
                "agent_rag", "rag_embeddings", "u", "p");

        List<SnapshotMigrator.Entry> entries = SnapshotMigrator.reflectEntries(store);
        assertEquals(2, entries.size());

        SnapshotMigrator.Entry first = entries.get(0);
        assertEquals("fixed-id-1", first.id());
        assertArrayEquals(vector, first.embedding().vector(), 0f);
        assertNotNull(first.segment());
        assertEquals("迁移片段内容", first.segment().text());
        assertEquals("u1", first.segment().metadata().getString(RagService.META_USER_ID));
        assertEquals(3, first.segment().metadata().getInteger(RagService.META_CHUNK_INDEX));

        // 第二条未指定 id 时库内自动生成 UUID
        assertNotNull(UUID.fromString(entries.get(1).id()));
    }
}
