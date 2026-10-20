package com.example.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词粗召（P1-9.3，仅 pgvector 存储模式可用）。
 *
 * 走 PostgreSQL：pg_trgm + ILIKE 对向量库 text 列做子串匹配。
 * 专门补纯向量的短板——编号/英文/专有名词（如 REF-2026-0001、Q3-退款单）精确命中；
 * 中文按二元组（bigram）切词，配合 trigram GIN 索引。
 *
 * 不参与打分：仅产出"关键词路排名"，与向量路排名在 {@link HybridContentRetriever} 做 RRF 融合。
 */
public class KeywordSearcher implements KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearcher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String table;

    public KeywordSearcher(String host, int port, String database, String table,
                           String user, String password) {
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        this.user = user;
        this.password = password;
        this.table = table;
    }

    @Override
    public String name() {
        return "pg_trgm";
    }

    @Override
    public boolean available() {
        return true;
    }

    /** pg_trgm 不需要单独索引：关键词直接查向量表的 text 列（已是同一份数据） */
    @Override
    public void indexAll(List<String> ids, List<TextSegment> segments) {
    }

    /** 向量表按 docId 删除时关键词数据同步消失，无需额外处理 */
    @Override
    public void removeByDocId(String docId) {
    }

    /**
     * 关键词检索：metadata.userId 精确 + text ILIKE 任一关键词。
     *
     * @param maxResults 关键词路返回条数
     */
    @Override
    public List<KeywordHit> search(String userId, String query, int maxResults) {
        List<String> terms = buildTerms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        // 排序：命中文档（知识库资料）优先于历史对话片段——对话片段通常更短，
        // 若单纯按长度排会把短对话顶在前面、把真正含编号的长文档挤出 TopK；
        // 同类型内短片段在前（命中更聚焦）。
        String sql = "SELECT embedding_id, text, metadata FROM " + table
                + " WHERE metadata->>'userId' = ? AND text ILIKE ANY (?)"
                + " ORDER BY (metadata->>'type' = 'document') DESC, char_length(text) ASC LIMIT ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            String[] patterns = terms.stream().map(t -> "%" + escapeLike(t) + "%").toArray(String[]::new);
            ps.setObject(2, conn.createArrayOf("text", patterns));
            ps.setInt(3, maxResults);
            List<KeywordHit> hits = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("embedding_id");
                    String text = rs.getString("text");
                    Metadata metadata = parseMetadata(rs.getObject("metadata"));
                    hits.add(new KeywordHit(id, TextSegment.from(text == null ? "" : text, metadata)));
                }
            }
            return hits;
        } catch (Exception e) {
            // 关键词路故障不影响向量路：上层记录后按纯向量召回
            log.warn("[RAG] 关键词检索失败，本轮跳过关键词路: {}", e.getMessage());
            return List.of();
        }
    }

    /** 建 trigram GIN 索引（表由 agent 角色创建，索引也归它；扩展需超级用户在部署脚本预建） */
    public void ensureTrgmIndex() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "CREATE EXTENSION IF NOT EXISTS pg_trgm")) {
                ps.execute();
            } catch (Exception e) {
                log.warn("[RAG] pg_trgm 扩展不可用（需超级用户在部署脚本预建），关键词路将退化为全表扫描: {}",
                        e.getMessage());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_" + table + "_content_trgm ON " + table
                            + " USING gin (text gin_trgm_ops)")) {
                ps.execute();
                log.info("[RAG] 关键词 trigram GIN 索引已就绪: idx_{}_content_trgm", table);
            }
        } catch (Exception e) {
            log.warn("[RAG] 创建 trigram 索引失败（不阻断启动，关键词检索仍可用但较慢）: {}", e.getMessage());
        }
    }

    /** query → 关键词集合（与 ES 实现共用同一套切词，见 {@link KeywordTerms}） */
    static List<String> buildTerms(String query) {
        return KeywordTerms.build(query);
    }

    /** 转义 ILIKE 通配符（% _ 与转义符本身） */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** JSONB 列读出来是 PGobject(jsonb)，转回 langchain4j Metadata */
    private static Metadata parseMetadata(Object raw) {
        if (raw == null) {
            return new Metadata();
        }
        try {
            String json = raw instanceof PGobject pg ? pg.getValue() : raw.toString();
            if (json == null || json.isBlank() || "null".equals(json)) {
                return new Metadata();
            }
            Map<String, Object> map = MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Metadata metadata = new Metadata();
            map.forEach((k, v) -> putTyped(metadata, k, v));
            return metadata;
        } catch (Exception e) {
            log.debug("[RAG] 关键词命中 metadata 解析失败，按空 metadata 处理: {}", e.getMessage());
            return new Metadata();
        }
    }

    /** 按 langchain4j Metadata 支持的类型做精确 put（JSON 数字拆箱为 int/long/double/float） */
    private static void putTyped(Metadata metadata, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            metadata.put(key, s);
        } else if (value instanceof Integer i) {
            metadata.put(key, i.intValue());
        } else if (value instanceof Long l) {
            metadata.put(key, l.longValue());
        } else if (value instanceof Double d) {
            metadata.put(key, d.doubleValue());
        } else if (value instanceof Float f) {
            metadata.put(key, f.floatValue());
        } else if (value instanceof java.util.UUID u) {
            metadata.put(key, u);
        } else {
            // 布尔/其他类型统一字符串化（观测用 metadata 不依赖其原始类型）
            metadata.put(key, value.toString());
        }
    }
}
