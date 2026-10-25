package com.aiagent.knowledge.infrastructure.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserReliabilityTest {

    @Test
    void shouldDecodeGb18030TextWhenUtf8ValidationFails() {
        TxtDocumentParser parser = new TxtDocumentParser();
        byte[] content = "退款流程与售后服务".getBytes(Charset.forName("GB18030"));

        String parsed = parser.parse(new ByteArrayInputStream(content));

        assertThat(parsed).contains("退款流程与售后服务");
    }

    @Test
    void shouldRouteTxtAndRejectLegacyDocFormat() {
        DocumentParserFactory factory = new DocumentParserFactory(List.of(
                new MarkdownDocumentParser(), new TxtDocumentParser(), new WordDocumentParser()));

        assertThat(factory.supports("knowledge.txt")).isTrue();
        assertThat(factory.supports("knowledge.markdown")).isTrue();
        assertThat(factory.supports("legacy.doc")).isFalse();
        assertThat(factory.supports("modern.docx")).isTrue();
    }

    @Test
    void shouldExtractDocxParagraphsAndTables() throws Exception {
        byte[] documentBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("售后规则");
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("退款时效");
            table.getRow(0).getCell(1).setText("3个工作日");
            document.write(outputStream);
            documentBytes = outputStream.toByteArray();
        }

        String parsed = new WordDocumentParser().parse(new ByteArrayInputStream(documentBytes));

        assertThat(parsed).contains("售后规则");
        assertThat(parsed).contains("退款时效 | 3个工作日");
    }

    @Test
    void shouldPreservePdfPageBoundaries() throws Exception {
        byte[] documentBytes;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(outputStream);
            documentBytes = outputStream.toByteArray();
        }

        String parsed = new PdfDocumentParser().parse(new ByteArrayInputStream(documentBytes));

        assertThat(parsed).contains("[Page 1]");
        assertThat(parsed).contains("[Page 2]");
    }

    @Test
    void pdfParseTest() throws Exception {
        File file = new File("C:\\Users\\Administrator\\Desktop\\“银狐”木马“甲蚁（G01）”团伙全量仿冒网站清单.pdf");

        PdfDocumentParser pdfDocumentParser = new PdfDocumentParser();

        try (FileInputStream fis = new FileInputStream(file)) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(fis.readAllBytes())){
                String parse = pdfDocumentParser.parse(bais);

                System.out.println("parse = " + parse);
            }

        }

    }

}
