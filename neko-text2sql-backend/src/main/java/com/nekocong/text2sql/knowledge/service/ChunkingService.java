package com.nekocong.text2sql.knowledge.service;

import com.nekocong.text2sql.knowledge.KnowledgeChunk;
import com.nekocong.text2sql.knowledge.parse.ParsedBlock;
import com.nekocong.text2sql.knowledge.parse.ParsedDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Splits a parsed document into retrievable chunks.
 *
 * <p>Rule (design D12): consecutive blocks are merged up to the chunk size <em>within one page</em>
 * and never across pages. A chunk therefore has exactly one {@code pageNo}, one bounding box (the
 * union of its blocks) and one character range, which is what makes highlighting a single rectangle.
 */
@Service
public class ChunkingService {

    /**
     * Chunks a parsed document.
     *
     * @param itemId the owning item
     * @param documentId the owning document, or {@code null}
     * @param document the parsed document
     * @param maxChars the maximum chunk length
     * @return the chunks, in reading order
     * @throws ChunkingException when a chunk cannot be given citation metadata
     */
    public List<KnowledgeChunk> chunk(
            String itemId, String documentId, ParsedDocument document, int maxChars) {
        int limit = Math.max(200, maxChars);
        List<ParsedBlock> blocks = document.blocks().stream()
                .filter(block -> block != null && block.text() != null && !block.text().isBlank())
                .sorted(Comparator.comparingInt(ParsedBlock::pageNo).thenComparingInt(ParsedBlock::charStart))
                .toList();
        if (blocks.isEmpty()) {
            throw new ChunkingException("解析结果没有可检索的文本片段");
        }

        boolean layout = document.pageCount() > 1
                || blocks.stream().anyMatch(block -> block.bbox() != null);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;
        for (List<ParsedBlock> page : groupByPage(blocks).values()) {
            for (List<ParsedBlock> group : groupBySize(page, limit)) {
                chunks.add(toChunk(itemId, documentId, index++, group, document.plainText(), layout));
            }
        }
        return chunks;
    }

    /**
     * Chunks a text-only item (business rules, agent prompts) that has no source document.
     *
     * @param itemId the owning item
     * @param text the text
     * @param maxChars the maximum chunk length
     * @return the chunks
     */
    public List<KnowledgeChunk> chunkText(String itemId, String text, int maxChars) {
        if (text == null || text.isBlank()) {
            throw new ChunkingException("内容为空，无法生成检索片段");
        }
        int limit = Math.max(200, maxChars);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + limit);
            // Prefer a paragraph boundary when one is close to the limit.
            int boundary = text.lastIndexOf('\n', end);
            if (boundary > start + limit / 2) {
                end = boundary;
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(new KnowledgeChunk(
                        UUID.randomUUID().toString(),
                        itemId,
                        null,
                        index++,
                        piece,
                        null,
                        null,
                        start,
                        end,
                        piece,
                        null));
            }
            start = end;
        }
        return chunks;
    }

    private static Map<Integer, List<ParsedBlock>> groupByPage(List<ParsedBlock> blocks) {
        Map<Integer, List<ParsedBlock>> pages = new LinkedHashMap<>();
        for (ParsedBlock block : blocks) {
            pages.computeIfAbsent(block.pageNo(), key -> new ArrayList<>()).add(block);
        }
        return pages;
    }

    private static List<List<ParsedBlock>> groupBySize(List<ParsedBlock> blocks, int limit) {
        List<List<ParsedBlock>> groups = new ArrayList<>();
        List<ParsedBlock> current = new ArrayList<>();
        int length = 0;
        for (ParsedBlock block : blocks) {
            String text = block.text().strip();
            if (text.length() > limit) {
                if (!current.isEmpty()) {
                    groups.add(current);
                    current = new ArrayList<>();
                    length = 0;
                }
                for (int offset = 0; offset < text.length(); offset += limit) {
                    int end = Math.min(text.length(), offset + limit);
                    groups.add(List.of(truncate(block, text.substring(offset, end), offset)));
                }
                continue;
            }
            if (!current.isEmpty() && length + text.length() + 1 > limit) {
                groups.add(current);
                current = new ArrayList<>();
                length = 0;
            }
            current.add(block);
            length += text.length() + 1;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * A single over-long block is cut into slices that keep the block's page and bbox; the character
     * range is narrowed to the slice so the quote still matches the text snapshot.
     */
    private static ParsedBlock truncate(ParsedBlock block, String slice, int offset) {
        return new ParsedBlock(
                block.blockId() + "-" + offset,
                block.type(),
                slice,
                block.pageNo(),
                block.bbox(),
                block.charStart() + offset,
                block.charStart() + offset + slice.length(),
                block.imagePath());
    }

    private static KnowledgeChunk toChunk(
            String itemId,
            String documentId,
            int index,
            List<ParsedBlock> group,
            String plainText,
            boolean layout) {
        ParsedBlock first = group.get(0);
        ParsedBlock last = group.get(group.size() - 1);
        String content = String.join("\n", group.stream().map(block -> block.text().strip()).toList());
        int charStart = first.charStart();
        int charEnd = last.charEnd();
        String quote = slice(plainText, charStart, charEnd, content);
        int[] bbox = union(group);
        Integer pageNo = first.pageNo() > 0 ? first.pageNo() : null;

        if (layout) {
            if (pageNo == null) {
                throw new ChunkingException("版面类文档的片段缺少页码，无法生成引用");
            }
            if (bbox == null) {
                throw new ChunkingException("版面类文档的片段缺少坐标框，无法生成引用");
            }
        }
        if (charStart < 0 || charEnd <= charStart) {
            throw new ChunkingException("文本片段缺少有效的字符区间，无法生成引用");
        }
        return new KnowledgeChunk(
                UUID.randomUUID().toString(),
                itemId,
                documentId,
                index,
                content,
                pageNo,
                bbox,
                charStart,
                charEnd,
                quote,
                null);
    }

    private static String slice(String plainText, int start, int end, String fallback) {
        if (plainText == null || start < 0 || end > plainText.length() || start >= end) {
            return fallback;
        }
        return plainText.substring(start, end);
    }

    private static int[] union(List<ParsedBlock> group) {
        int[] result = null;
        for (ParsedBlock block : group) {
            int[] box = block.bbox();
            if (box == null || box.length < 4) {
                continue;
            }
            if (result == null) {
                result = new int[] {box[0], box[1], box[2], box[3]};
            } else {
                result[0] = Math.min(result[0], box[0]);
                result[1] = Math.min(result[1], box[1]);
                result[2] = Math.max(result[2], box[2]);
                result[3] = Math.max(result[3], box[3]);
            }
        }
        return result;
    }
}
