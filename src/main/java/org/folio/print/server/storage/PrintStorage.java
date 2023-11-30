package org.folio.print.server.storage;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowIterator;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.RowStream;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.print.server.data.PrintEntry;
import org.folio.print.server.data.PrintEntryType;
import org.folio.tlib.postgres.PgCqlDefinition;
import org.folio.tlib.postgres.PgCqlQuery;
import org.folio.tlib.postgres.TenantPgPool;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldText;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldTimestamp;
import org.folio.tlib.postgres.cqlfield.PgCqlFieldUuid;

public class PrintStorage {

  private static final Logger log = LogManager.getLogger(PrintStorage.class);

  private static final String CREATE_IF_NO_EXISTS = "CREATE TABLE IF NOT EXISTS ";
  private static final String WHERE_BY_ID = " WHERE id = $1";

  private final TenantPgPool pool;

  private final String printTable;


  /**
   * Construct storage request for a user with given okapi permissions.
   *
   * @param vertx       Vert.x handle
   * @param tenant      tenant
   */
  public PrintStorage(Vertx vertx, String tenant) {
    this.pool = TenantPgPool.pool(vertx, tenant);
    this.printTable = pool.getSchema() + ".printing";
  }

  /**
   * Prepares storage for a tenant, AKA tenant init.
   *
   * @return async result
   */
  public Future<Void> init() {
    return pool.execute(List.of(
        CREATE_IF_NO_EXISTS + printTable
            + "(id uuid NOT NULL PRIMARY KEY,"
            + " created TIMESTAMP NOT NULL,"
            + " type VARCHAR NOT NULL,"
            + " sorting_field VARCHAR NULL,"
            + " content VARCHAR NOT NULL"
            + ")"
    ));
  }

  PrintEntry fromRow(Row row) {
    PrintEntry entry = fromRowWithoutContent(row);
    entry.setContent(row.getString("content"));
    return entry;
  }

  PrintEntry fromRowWithoutContent(Row row) {
    PrintEntry entry = new PrintEntry();
    entry.setId(row.getUUID("id"));
    entry.setCreated(row.getLocalDateTime("created").atZone(ZoneId.of(ZoneOffset.UTC.getId())));
    entry.setType(PrintEntryType.valueOf(row.getString("type")));
    entry.setSortingField(row.getString("sorting_field"));
    return entry;
  }

  /**
   * Create print entry.
   *
   * @param entry to be created
   * @return async result with success if created; failed otherwise
   */
  public Future<Void> createEntry(PrintEntry entry) {
    return pool.preparedQuery(
            "INSERT INTO " + printTable
                + " (id, created, type, sorting_field, content)"
                + " VALUES ($1, $2, $3, $4, $5)"
        )
        .execute(Tuple.of(entry.getId(), toLocalDateTime(entry.getCreated()),
            entry.getType(), entry.getSortingField(), entry.getContent()))
        .map(rowSet -> {
          if (rowSet.rowCount() == 0) {
            throw new EntryException("Failed to create");
          }
          return null;
        });
  }

  private LocalDateTime toLocalDateTime(ZonedDateTime zonedDateTime) {
    return zonedDateTime == null ? null :
        zonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  /**
   * Get print entry.
   *
   * @param id entry identifier
   * @return async result with entry value; failure otherwise
   */
  public Future<PrintEntry> getEntry(UUID id) {
    return getEntryWoCheck(id)
        .map(entry -> {
          if (entry == null) {
            throw new NotFoundException();
          }
          return entry;
        });
  }

  Future<PrintEntry> getEntryWoCheck(UUID id) {
    return pool.preparedQuery(
            "SELECT * FROM " + printTable + WHERE_BY_ID)
        .execute(Tuple.of(id))
        .map(rowSet -> {
          RowIterator<Row> iterator = rowSet.iterator();
          if (!iterator.hasNext()) {
            return null;
          }
          return fromRow(iterator.next());
        });
  }

  /**
   * Delete print entry.
   *
   * @param id entry identifier
   * @return async result; exception if not found or forbidden
   */
  public Future<Void> deleteEntry(UUID id) {
    return getEntryWoCheck(id).compose(entry -> {
      if (entry == null) {
        return Future.failedFuture(new NotFoundException());
      }
      return pool.preparedQuery(
              "DELETE FROM " + printTable + WHERE_BY_ID)
          .execute(Tuple.of(id))
          .map(res -> {
            if (res.rowCount() == 0) {
              throw new NotFoundException();
            }
            return null;
          });
    });
  }

  /**
   * Update print entry.
   *
   * @param entry to be updated
   * @return async result with success if created; failed otherwise
   */
  public Future<Void> updateEntry(PrintEntry entry) {
    return pool.preparedQuery(
            "UPDATE " + printTable
                + " SET created = $2, type = $3, sorting_field = $4, content = $5"
                + WHERE_BY_ID
        )
        .execute(Tuple.of(entry.getId(), toLocalDateTime(entry.getCreated()),
            entry.getType(), entry.getSortingField(), entry.getContent()))
        .map(rowSet -> {
          if (rowSet.rowCount() == 0) {
            throw new NotFoundException();
          }
          return null;
        })
        .recover(e -> {
          if (e instanceof PgException pgException
              && pgException.getMessage().contains("(23505)")) {
            return Future.failedFuture(new NotFoundException());

          }
          return Future.failedFuture(e);
        })
        .mapEmpty();
  }

  /**
   * Get entries with optional cqlQuery.
   *
   * @param response HTTP response for result
   * @param cqlQuery CQL cqlQuery; null if no cqlQuery is provided
   * @param offset   starting offset of entries returned
   * @param limit    maximum number of entries returned
   * @return async result
   */
  public Future<Void> getEntries(HttpServerResponse response, String cqlQuery,
                                 int offset, int limit) {

    Pair<String, String> sqlQuery = createSqlQuery(cqlQuery, offset, limit);
    String countQuery = "SELECT COUNT(*) FROM " + sqlQuery.getRight();
    return pool.getConnection()
        .compose(connection ->
            streamResult(response, connection, sqlQuery.getLeft(), countQuery)
                .onFailure(x -> connection.close())
        );
  }

  Future<Void> streamResult(HttpServerResponse response,
                            SqlConnection connection, String query, String cnt) {

    String property = "items";
    Tuple tuple = Tuple.tuple();
    int sqlStreamFetchSize = 100;

    return connection.prepare("SELECT id, created, type, sorting_field FROM " + query)
        .compose(pq ->
            connection.begin().map(tx -> {
              response.setChunked(true);
              response.putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
              response.write("{ \"" + property + "\" : [");
              AtomicBoolean first = new AtomicBoolean(true);
              RowStream<Row> stream = pq.createStream(sqlStreamFetchSize, tuple);
              stream.handler(row -> {
                if (!first.getAndSet(false)) {
                  response.write(",");
                }
                PrintEntry entry = fromRowWithoutContent(row);
                response.write(JsonObject.mapFrom(entry).encode());
              });
              stream.endHandler(end -> {
                Future<RowSet<Row>> cntFuture = cnt != null
                    ? connection.preparedQuery(cnt).execute(tuple)
                    : Future.succeededFuture(null);
                cntFuture
                    .onSuccess(cntRes -> resultFooter(response, cntRes, null))
                    .onFailure(f -> {
                      log.error(f.getMessage(), f);
                      resultFooter(response, null, f.getMessage());
                    })
                    .eventually(x -> tx.commit().compose(y -> connection.close()));
              });
              stream.exceptionHandler(e -> {
                log.error("stream error {}", e.getMessage(), e);
                resultFooter(response, null, e.getMessage());
                tx.commit().compose(y -> connection.close());
              });
              return null;
            })
        );
  }

  void resultFooter(HttpServerResponse response, RowSet<Row> rowSet, String diagnostic) {
    JsonObject resultInfo = new JsonObject();
    if (rowSet != null) {
      int pos = 0;
      Row row = rowSet.iterator().next();
      int count = row.getInteger(pos);
      resultInfo.put("totalRecords", count);
    }
    JsonArray diagnostics = new JsonArray();
    if (diagnostic != null) {
      diagnostics.add(new JsonObject().put("message", diagnostic));
    }
    resultInfo.put("diagnostics", diagnostics);
    response.write("], \"resultInfo\": " + resultInfo.encode() + "}");
    response.end();
  }

  /**
   * Get print entries as list by query.
   * @param cqlQuery Query to perform
   * @param offset Offset
   * @param limit Limit
   * @return Result list
   */
  public Future<List<PrintEntry>> getEntriesByQuery(String cqlQuery, int offset, int limit) {

    Pair<String, String> sqlQuery = createSqlQuery(cqlQuery, offset, limit);

    return pool.preparedQuery("SELECT * FROM " + sqlQuery.getLeft())
        .execute()
        .map(rowSet -> {
          RowIterator<Row> iterator = rowSet.iterator();
          List<PrintEntry> results = new ArrayList<>();
          while (iterator.hasNext()) {
            results.add(fromRow(iterator.next()));
          }
          return results;
        });
  }

  private Pair<String, String> createSqlQuery(String cqlQuery, int offset, int limit) {
    PgCqlDefinition definition = PgCqlDefinition.create();
    definition.addField("id", new PgCqlFieldUuid());
    definition.addField("type", new PgCqlFieldText().withExact());
    definition.addField("created", new PgCqlFieldTimestamp());
    definition.addField("sortingField", new PgCqlFieldText().withColumn("sorting_field"));

    PgCqlQuery pgCqlQuery = definition.parse(cqlQuery);
    String sqlOrderBy = pgCqlQuery.getOrderByClause();
    String from = printTable + " WHERE "
        + (pgCqlQuery.getWhereClause() == null ? "1 = 1" : pgCqlQuery.getWhereClause());
    String sqlQuery = from
        + (sqlOrderBy == null ? "" : " ORDER BY " + sqlOrderBy)
        + " LIMIT " + limit + " OFFSET " + offset;

    log.debug("createSqlQuery: SQL: {}", sqlQuery);
    return Pair.of(sqlQuery, from);
  }
}
