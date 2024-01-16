package org.folio.print.server.service;

import static org.junit.Assert.*;

import org.apache.pdfbox.util.Hex;
import org.folio.print.server.data.PrintEntry;
import org.junit.Test;
import org.xhtmlrenderer.util.XRRuntimeException;

import java.util.ArrayList;
import java.util.List;

public class PdfServiceTest {

  @Test
  public void createPdfFile(){
    byte[] result = PdfService.createPdfFile("<div><p>PDF file</p></div><br><p>Content &nbsp; &lt; &gt; "
        + "&amp; &quot; &apos; &ndash; &mdash; &copy; &reg; &nbsp; &trade;</p>");
    assertTrue(result.length > 0);
  }

  @Test
  public void createPdfFileEmpty(){
    byte[] result = PdfService.createPdfFile("");
    assertEquals(0, result.length);
  }

  @Test
  public void createPdfFileInvalidHtml(){
    Exception exception = assertThrows(XRRuntimeException.class, () -> {
      PdfService.createPdfFile("<div><p>PDF file");
    });

    String expectedMessage = "Can't load the XML resource";
    String actualMessage = exception.getMessage();

    assertTrue(actualMessage.contains(expectedMessage));
  }

  @Test
  public void combinePdfFiles(){
    byte[] pdf = PdfService.createPdfFile("<div><p>PDF file</p></div><br><p>Content</p>");
    List<PrintEntry> entries = new ArrayList<>();
    PrintEntry entry = new PrintEntry();
    entry.setContent(Hex.getString(pdf));
    PrintEntry entry2 = new PrintEntry();
    entry2.setContent(Hex.getString(pdf));
    PrintEntry empty = new PrintEntry();
    empty.setContent("");
    PrintEntry nullEntry = new PrintEntry();
    entries.add(entry);
    entries.add(entry2);
    entries.add(empty);
    entries.add(nullEntry);

    byte[] result = PdfService.combinePdfFiles(entries);
    assertTrue(result.length > 0);
  }

  @Test
  public void combinePdfFilesEmptyList(){
    byte[] result = PdfService.combinePdfFiles(new ArrayList<>());
    assertEquals(0, result.length);
  }
}
