package com.nekocong.text2sql.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.parse.ParsedBlock;
import com.nekocong.text2sql.knowledge.parse.ParsedDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the chunk rule that makes citations possible: blocks merge only inside one page, and each
 * chunk keeps one page number, one bounding box union, one character range and its exact quote.
 */
class ChunkingServiceTest {

    private final ChunkingService chunking = new ChunkingService();

    @Test
    void mergesBlocksWithinAPageButNeverAcrossPages() {
        String pageOne = "第一页标题\n第一页正文。";
        String pageTwo = "第二页正文。";
        String plainText = pageOne + "\n" + pageTwo;
        int pageTwoStart = plainText.indexOf(pageTwo);
        ParsedDocument document = new ParsedDocument(
                plainText,
                List.of(
                        block("b1", "title", "第一页标题", 1, new int[] {100, 100, 900, 150}, 0),
                        block("b2", "text", "第一页正文。", 1, new int[] {100, 200, 900, 300}, 6),
                        block("b3", "text", pageTwo, 2, new int[] {50, 100, 950, 400}, pageTwoStart)),
                2,
                "mineru",
                "v4",
                Map.of());

        List<KnowledgeChunk> chunks = chunking.chunk("item-1", "doc-1", document, 4000);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).pageNo()).isEqualTo(1);
        assertThat(chunks.get(0).bbox()).containsExactly(100, 100, 900, 300);
        assertThat(chunks.get(0).quote()).isEqualTo(pageOne);
        assertThat(chunks.get(1).pageNo()).isEqualTo(2);
        assertThat(chunks.get(1).bbox()).containsExactly(50, 100, 950, 400);
        assertThat(chunks.get(1).quote()).isEqualTo(pageTwo);
        for (KnowledgeChunk chunk : chunks) {
            assertThat(plainText.substring(chunk.charStart(), chunk.charEnd()))
                    .isEqualTo(chunk.quote());
        }
    }

    @Test
    void splitsAnOverLongBlockButKeepsItsPageAndBox() {
        String text = "x".repeat(900);
        ParsedDocument document = new ParsedDocument(
                text,
                List.of(block("b1", "text", text, 3, new int[] {10, 20, 300, 400}, 0)),
                3,
                "mineru",
                "v4",
                Map.of());

        List<KnowledgeChunk> chunks = chunking.chunk("item-1", "doc-1", document, 400);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (KnowledgeChunk chunk : chunks) {
            assertThat(chunk.pageNo()).isEqualTo(3);
            assertThat(chunk.bbox()).containsExactly(10, 20, 300, 400);
            assertThat(text.substring(chunk.charStart(), chunk.charEnd()))
                    .isEqualTo(chunk.quote());
        }
    }

    @Test
    void rejectsLayoutBlocksWithoutCitationMetadata() {
        ParsedDocument document = new ParsedDocument(
                "正文",
                List.of(block("b1", "text", "正文", 1, null, 0)),
                2,
                "mineru",
                "v4",
                Map.of());

        assertThatThrownBy(() -> chunking.chunk("item-1", "doc-1", document, 800))
                .isInstanceOf(ChunkingException.class)
                .hasMessageContaining("坐标");
    }

    @Test
    void chunksPlainTextWithSlicesThatMatchTheQuote() {
        String text = "第一条业务规则：挂科指成绩低于 60 分。\n第二条：统计口径按学期。";

        List<KnowledgeChunk> chunks = chunking.chunkText("item-2", text, 20);

        assertThat(chunks).isNotEmpty();
        for (KnowledgeChunk chunk : chunks) {
            assertThat(chunk.documentId()).isNull();
            assertThat(chunk.pageNo()).isNull();
            assertThat(chunk.bbox()).isNull();
            assertThat(text.substring(chunk.charStart(), chunk.charEnd())).contains(chunk.content());
        }
    }

    private static ParsedBlock block(
            String id, String type, String text, int pageNo, int[] bbox, int start) {
        return new ParsedBlock(id, type, text, pageNo, bbox, start, start + text.length(), null);
    }
}
