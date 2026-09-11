package com.example.agent.observability.model;

/**
 * 一个 RAG 召回片段。
 *
 * @param docId  文档 id（历史对话片段可能为空）
 * @param source 来源名（文件名 / "历史对话"）
 * @param type   来源类型：document / conversation
 * @param text   片段文本（截断 100 字符，单事件 <2KB）
 * @param score  向量相似度分数（0~1）
 */
public record RagSegment(String docId,
                         String source,
                         String type,
                         String text,
                         double score) {
}
