package com.nekocong.text2sql.knowledge.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekocong.text2sql.config.KnowledgeProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentParserSelectorTest {

    @Test
    void pdfWithMineruAndBlankTokenFailsWithReadableReason() {
        KnowledgeProperties properties = properties("mineru", "mineru", "");
        DocumentParserSelector selector = new DocumentParserSelector(properties, parsers(properties));

        ParseException exception = assertThrows(ParseException.class,
                () -> selector.select(new ParseContext(
                        "manual.pdf", "application/pdf", new byte[]{1}, null)));

        assertTrue(exception.getMessage().contains("MinerU"));
        assertTrue(exception.getMessage().contains("Token"));
    }

    @Test
    void officeDocumentsUseTika() {
        KnowledgeProperties properties = properties("mineru", "mineru", "");
        DocumentParserSelector selector = new DocumentParserSelector(properties, parsers(properties));

        DocumentParser parser = selector.select(new ParseContext(
                "manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1},
                null));

        assertEquals("tika", parser.engine());
    }

    @Test
    void pdfCanExplicitlyUseTika() {
        KnowledgeProperties properties = properties("tika", "mineru", "");
        DocumentParserSelector selector = new DocumentParserSelector(properties, parsers(properties));

        DocumentParser parser = selector.select(new ParseContext(
                "manual.pdf", "application/pdf", new byte[]{1}, null));

        assertEquals("tika", parser.engine());
    }

    @Test
    void explicitUnknownEngineFails() {
        KnowledgeProperties properties = properties("mineru", "mineru", "");
        DocumentParserSelector selector = new DocumentParserSelector(properties, parsers(properties));

        assertThrows(ParseException.class, () -> selector.select(
                "unknown", new ParseContext("a.txt", "text/plain", new byte[]{1}, null)));
    }

    @Test
    void listsEnginesAndReportsFormatSupport() {
        KnowledgeProperties properties = properties("mineru", "mineru", "");
        DocumentParserSelector selector = new DocumentParserSelector(properties, parsers(properties));

        assertTrue(selector.engines().contains("tika"));
        assertTrue(selector.engines().contains("mineru"));
        assertTrue(selector.supports(new ParseContext("a.txt", "text/plain", new byte[]{1}, null)));
        assertTrue(selector.supports(new ParseContext("a.pdf", "application/pdf", new byte[]{1}, null)));
        assertTrue(selector.supports(new ParseContext("a.png", "image/png", new byte[]{1}, null)));
    }

    private static KnowledgeProperties properties(String pdfEngine, String imageEngine, String token) {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.getParser().setPdfEngine(pdfEngine);
        properties.getParser().setImageEngine(imageEngine);
        properties.getMineru().setToken(token);
        return properties;
    }

    private static List<DocumentParser> parsers(KnowledgeProperties properties) {
        MinerUClient unavailable = new MinerUClient() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public MinerUResult parse(ParseContext context) {
                throw new ParseException("MinerU 不可用");
            }
        };
        return List.of(
                new TikaDocumentParser(properties),
                new MinerUDocumentParser(properties, unavailable));
    }
}
