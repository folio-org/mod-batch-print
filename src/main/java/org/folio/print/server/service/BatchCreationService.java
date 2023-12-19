package org.folio.print.server.service;

import io.vertx.ext.web.RoutingContext;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.util.Hex;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.print.server.data.PrintEntry;
import org.folio.print.server.data.PrintEntryType;
import org.folio.print.server.storage.PrintStorage;

public class BatchCreationService {
  private static final Logger LOGGER = LogManager.getLogger(BatchCreationService.class);
  private static final int MAX_COUNT_IN_BATCH = 1000;

  private BatchCreationService() {
  }

  /**
   * Process batch creation request.
   * @param ctx Batch creation request context
   */
  public static void process(RoutingContext ctx) {
    String tenant = ctx.request().getHeader(XOkapiHeaders.TENANT);
    PrintStorage printStorage = new PrintStorage(ctx.vertx(), tenant);
    LocalDateTime localDateTime = LocalDateTime.now().minusDays(1).minusMinutes(5);
    LOGGER.info("process:: tenant {}, from {}", tenant, localDateTime);

    printStorage.getEntriesByQuery("type=\"SINGLE\" and created > " + localDateTime
                    + " sortby sortingField created", 0, MAX_COUNT_IN_BATCH)
            .onSuccess(l -> processListAndSaveResult(l, printStorage))
            .onFailure(e -> LOGGER.error("Failed to create print batch", e));
    ctx.response().setStatusCode(204);
    ctx.response().end();
  }

  private static void processListAndSaveResult(List<PrintEntry> entries, PrintStorage storage) {
    LOGGER.info("processListAndSaveResult:: {} entries will be processed", entries.size());
    if (!entries.isEmpty()) {
      byte[] merged = PdfService.combinePdfFiles(entries);
      PrintEntry batch = new PrintEntry();
      batch.setId(UUID.randomUUID());
      batch.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
      batch.setType(PrintEntryType.BATCH);
      batch.setContent(Hex.getString(merged));
      storage.createEntry(batch);
      List<UUID> ids = entries.stream().map(PrintEntry::getId).toList();
      storage.deleteEntries(ids);
    }
  }
}
