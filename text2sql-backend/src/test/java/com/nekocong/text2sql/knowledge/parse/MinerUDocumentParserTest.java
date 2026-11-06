package com.nekocong.text2sql.knowledge.parse;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekocong.text2sql.config.KnowledgeProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MinerUDocumentParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsContentListWithPagesAndBbox() throws IOException {
        byte[] contentList = readResource("/mineru/content_list.json");
        byte[] fullMarkdown = readResource("/mineru/full.md");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("full.md", fullMarkdown);
        entries.put("content_list.json", contentList);
        entries.put("images/pic.png", new byte[]{1, 2, 3});
        byte[] zip = zip(entries);

        MinerUClient client = new MinerUClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public MinerUResult parse(ParseContext context) {
                return new MinerUResult(zip, "v4");
            }
        };
        MinerUDocumentParser parser = new MinerUDocumentParser(new KnowledgeProperties(), client);

        ParsedDocument document = parser.parse(
                new ParseContext("sample.pdf", "application/pdf", new byte[]{1}, null));

        assertEquals("mineru", document.engine());
        assertEquals(2, document.pageCount());
        assertEquals(4, document.blocks().size());
        assertEquals(1, document.blocks().get(0).pageNo());
        assertEquals(1, document.blocks().get(1).pageNo());
        assertEquals(2, document.blocks().get(2).pageNo());
        assertEquals(2, document.blocks().get(3).pageNo());
        assertArrayEquals(new int[]{100, 100, 900, 150}, document.blocks().get(0).bbox());
        assertArrayEquals(new int[]{50, 100, 950, 400}, document.blocks().get(2).bbox());
        assertEquals("图一", document.blocks().get(3).text());
        assertEquals("images/pic.png", document.blocks().get(3).imagePath());
        assertTrue(document.derivedFiles().containsKey("full.md"));
        assertTrue(document.derivedFiles().containsKey("content.json"));
        assertTrue(document.derivedFiles().containsKey("images/pic.png"));
        assertBlockRanges(document);
    }

    @Test
    void mapsContentListDirectly() throws IOException {
        JsonNode contentList = objectMapper.readTree(readResource("/mineru/content_list.json"));

        List<ParsedBlock> blocks = MinerUDocumentParser.mapContentList(contentList);

        assertEquals(4, blocks.size());
        assertEquals("第一页标题", blocks.get(0).text());
        assertEquals(1, blocks.get(0).pageNo());
        assertEquals("第一页正文。", blocks.get(1).text());
        assertEquals(2, blocks.get(2).pageNo());
        assertArrayEquals(new int[]{50, 100, 950, 400}, blocks.get(2).bbox());
        assertEquals("<table><tr><td>A</td><td>B</td></tr></table>", blocks.get(2).text());
        assertEquals("图一", blocks.get(3).text());
        assertEquals("images/pic.png", blocks.get(3).imagePath());

        String plainText = blocks.stream()
                .map(ParsedBlock::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        for (ParsedBlock block : blocks) {
            assertEquals(block.text(), plainText.substring(block.charStart(), block.charEnd()));
        }
    }

    private static void assertBlockRanges(ParsedDocument document) {
        for (ParsedBlock block : document.blocks()) {
            assertEquals(block.text(),
                    document.plainText().substring(block.charStart(), block.charEnd()),
                    "block " + block.blockId() + " range must slice back to its text");
        }
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream input = MinerUDocumentParserTest.class.getResourceAsStream(name)) {
            assertNotNull(input, "missing test resource " + name);
            return input.readAllBytes();
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(UTF_8);
    }
}
