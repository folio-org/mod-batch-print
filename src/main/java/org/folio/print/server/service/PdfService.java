package org.folio.print.server.service;

import com.lowagie.text.DocumentException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Hex;
import org.folio.print.server.data.PrintEntry;
import org.xhtmlrenderer.pdf.ITextRenderer;

public class PdfService {
  private static final Logger LOGGER = LogManager.getLogger(PdfService.class);

  private PdfService() {
  }

  /**
   * Create PDF content from HTML input.
   * @param htmlContent HTML input
   * @return Byte array of PDF content
   */
  public static byte[] createPdfFile(String htmlContent) {
    if (htmlContent != null && !htmlContent.isBlank()) {
      try (PDDocument document = new PDDocument();
           ByteArrayOutputStream os = new ByteArrayOutputStream()) {
        htmlContent = cleanHtmlData(htmlContent);
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        ITextRenderer renderer = new ITextRenderer();
        renderer.setDocumentFromString(htmlContent);
        renderer.layout();
        renderer.createPDF(os);
        return os.toByteArray();
      } catch (IOException | DocumentException e) {
        LOGGER.error("Error creating PDF", e);
      }
    }
    return new byte[0];
  }

  private static String cleanHtmlData(String htmlContent) {
    return "<div>" + htmlContent
        .replace("<br>", "<br/>")
        .replace("&nbsp;", "&#160;")
        .replace("&lt;", "&#60;")
        .replace("&gt;", "&#62;")
        .replace("&amp;", "&#38;")
        .replace("&quot;", "&#34;")
        .replace("&apos;", "&#39;")
        .replace("&ndash;", "&#8211;")
        .replace("&mdash;", "&#8212;")
        .replace("&copy;", "&#169;")
        .replace("&reg;", "&#174;")
        .replace("&nbsp;", "&#160;")
        .replace("&trade;", "&#8482;")
        + "</div>";
  }

  /**
   * Combine single print entries in batch print file.
   * @param entries Entries to combine
   * @return Byte array of combined PDF file
   */
  public static byte[] combinePdfFiles(List<PrintEntry> entries) {
    if (!entries.isEmpty()) {
      try (ByteArrayOutputStream mergedOutputStream = new ByteArrayOutputStream()) {
        PDFMergerUtility pdfMerger = new PDFMergerUtility();
        entries.forEach(e -> {
          if (e.getContent() != null && !e.getContent().isBlank()) {
            try {
              pdfMerger.addSource(new ByteArrayInputStream(Hex.decodeHex(e.getContent())));
            } catch (IOException ex) {
              LOGGER.error("Failed to merge entry: " + e.getId(), ex);
            }
          }
        });
        pdfMerger.setDestinationStream(mergedOutputStream);
        pdfMerger.mergeDocuments(null);
        return mergedOutputStream.toByteArray();
      } catch (IOException e) {
        LOGGER.error("Error merging PDFs", e);
      }
    }
    return  new byte[0];
  }
}
