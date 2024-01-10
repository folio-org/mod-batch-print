package org.folio.print.server.service;

import com.lowagie.text.DocumentException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
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
        htmlContent = "<div>" + htmlContent + "</div>";
        htmlContent = htmlContent.replace("<br>", "<br/>");
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
            pdfMerger.addSource(new RandomAccessReadBuffer(Hex.decodeHex(e.getContent())));
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
