package com.nekocong.text2sql.knowledge.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekocong.text2sql.config.KnowledgeProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class TikaDocumentParserTest {

    private final TikaDocumentParser parser = new TikaDocumentParser(new KnowledgeProperties());

    @Test
    void parsesPlainTextAndKeepsExactCharacterRanges() {
        byte[] bytes = "第一段内容。\n\n第二段内容。\n\n第三段内容。".getBytes(UTF_8);

        ParsedDocument document = parser.parse(
                new ParseContext("sample.txt", "text/plain", bytes, null));

        assertFalse(document.plainText().isBlank());
        assertFalse(document.blocks().isEmpty());
        assertEquals(1, document.pageCount());
        assertEquals("tika", document.engine());
        assertTrue(document.derivedFiles().containsKey("full.md"));
        assertTrue(document.derivedFiles().containsKey("content.json"));
        assertTrue(document.plainText().contains("第二段内容"));
        assertBlockRanges(document);
    }

    @Test
    void parsesHtmlBodyText() {
        byte[] bytes = ("<html><head><meta charset=\"utf-8\"></head><body>"
                + "<h1>季度报告</h1><p>这是正文段落。</p></body></html>").getBytes(UTF_8);

        ParsedDocument document = parser.parse(
                new ParseContext("report.html", "text/html", bytes, null));

        assertTrue(document.plainText().contains("季度报告"));
        assertTrue(document.plainText().contains("这是正文段落"));
        assertBlockRanges(document);
    }

    @Test
    void rejectsDocumentsWithoutUsableText() {
        byte[] bytes = "   \n\n  ".getBytes(UTF_8);

        ParseException exception = assertThrows(ParseException.class,
                () -> parser.parse(new ParseContext("empty.txt", "text/plain", bytes, null)));

        assertTrue(exception.getMessage().contains("未从文档中提取到可用文本"));
    }

    private static void assertBlockRanges(ParsedDocument document) {
        List<ParsedBlock> blocks = document.blocks();
        for (ParsedBlock block : blocks) {
            assertEquals(block.text(),
                    document.plainText().substring(block.charStart(), block.charEnd()),
                    "block " + block.blockId() + " range must slice back to its text");
            assertEquals(1, block.pageNo());
        }
    }
}
