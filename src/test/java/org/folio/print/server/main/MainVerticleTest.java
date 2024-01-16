package org.folio.print.server.main;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.junit.VertxUnitRunner;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.print.server.TestBase;
import org.folio.print.server.data.Message;
import org.folio.print.server.data.PrintEntry;
import org.folio.print.server.data.PrintEntryType;
import org.folio.print.server.service.PrintService;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@RunWith(VertxUnitRunner.class)
public class MainVerticleTest extends TestBase {

  private JsonArray permRead = new JsonArray().add("mod-batch-print.print.read");
  private JsonArray permWrite = new JsonArray().add("mod-batch-print.print.write");
  @Test
  public void testAdminHealth() {
    RestAssured.given()
        .baseUri(MODULE_URL)
        .get("/admin/health")
        .then()
        .statusCode(200)
        .contentType(ContentType.TEXT);
  }

  @Test
  public void testCrudGlobalOk() {
    PrintEntry entry = new PrintEntry();
    entry.setContent("AA");
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);
    entry.setSortingField("Last,User");

    JsonObject en = JsonObject.mapFrom(entry);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .post("/print/entries")
        .then()
        .statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .get("/print/entries/" + entry.getId())
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("id", is(entry.getId().toString()))
        .body("content", is(entry.getContent()))
        .body("type", is(entry.getType().toString()))
        .body("sortingField", is(entry.getSortingField()));

    entry.setContent("BB");
    en = JsonObject.mapFrom(entry);
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .put("/print/entries/" + entry.getId())
        .then()
        .statusCode(204);


    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .delete("/print/entries/" + entry.getId())
        .then()
        .statusCode(204);
  }

  @Test
  public void testPostMissingTenant() {
    PrintEntry entry = new PrintEntry();
    entry.setContent("AA");
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);

    JsonObject en = JsonObject.mapFrom(entry);
    JsonArray permWrite = new JsonArray().add("mod-batch-print.print.write");

    RestAssured.given()
        .baseUri(MODULE_URL)  // if not, Okapi will intercept
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .post("/print/entries")
        .then()
        .statusCode(400)
        .contentType(ContentType.TEXT)
        .body(containsString("Missing parameter X-Okapi-Tenant"));
  }

  @Test
  public void testMissingPermissionsHeader() {
    PrintEntry entry = new PrintEntry();
    entry.setContent("AA");
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);

    JsonObject en = JsonObject.mapFrom(entry);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .contentType(ContentType.JSON)
        .body(en.encode())
        .post("/print/entries")
        .then()
        .statusCode(400)
        .contentType(ContentType.TEXT)
        .body(containsString("Missing parameter X-Okapi-Permissions in HEADER"));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .contentType(ContentType.JSON)
        .get("/print/entries")
        .then()
        .statusCode(400)
        .contentType(ContentType.TEXT)
        .body(containsString("Missing parameter X-Okapi-Permissions in HEADER"));
  }

  @Test
  public void testUpdateWithWrongId() {
    PrintEntry entry = new PrintEntry();
    entry.setContent("AA");
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);
    entry.setSortingField("Last,User");

    JsonObject en = JsonObject.mapFrom(entry);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .post("/print/entries")
        .then()
        .statusCode(204);


    entry.setContent("BB");
    en = JsonObject.mapFrom(entry);
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .put("/print/entries/" + UUID.randomUUID())
        .then()
        .statusCode(400);
  }

  @Test
  public void testPostInvalidEntry() {
    PrintEntry entry = new PrintEntry();
    entry.setId(UUID.randomUUID());

    JsonObject en = JsonObject.mapFrom(entry);
    JsonArray permWrite = new JsonArray().add("mod-batch-print.print.write");

    RestAssured.given()
        .baseUri(MODULE_URL)  // if not, Okapi will intercept
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .post("/print/entries")
        .then()
        .statusCode(400)
        .contentType(ContentType.TEXT)
        .body(containsString("provided object should contain property created"));
  }

  @Test
  public void testPostBodyTooBig() {
    PrintEntry entry = new PrintEntry();
    entry.setContent("A".repeat(PrintService.BODY_LIMIT));
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);

    JsonObject en = JsonObject.mapFrom(entry);

    RestAssured.given()
        .baseUri(MODULE_URL)  // if not, Okapi will intercept
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .post("/print/entries")
        .then()
        .statusCode(413)
        .contentType(ContentType.TEXT)
        .body(is("Request Entity Too Large"));
  }

  @Test
  public void testNotFound() {
    PrintEntry entry = new PrintEntry();
    entry.setContent("AA");
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);

    JsonObject en = JsonObject.mapFrom(entry);
    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .get("/print/entries/" + UUID.randomUUID())
        .then()
        .statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .delete("/print/entries/" + UUID.randomUUID())
        .then()
        .statusCode(404);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(en.encode())
        .put("/print/entries/" + en.getString("id"))
        .then()
        .statusCode(404);
  }

  @Test
  public void testGetPrintEntries() {
    List<String> ids = new ArrayList<>();
    PrintEntry entry = new PrintEntry();
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setType(PrintEntryType.SINGLE);
    for (int i = 0; i < 3; i++) {
      entry.setId(UUID.randomUUID());
      entry.setContent("A" + i);
      entry.setSortingField("A" + (5 - i));
      entry.setType(i % 2 == 0 ? PrintEntryType.SINGLE : PrintEntryType.BATCH);
      JsonObject en = JsonObject.mapFrom(entry);
      ids.add(entry.getId().toString());
      RestAssured.given()
          .header(XOkapiHeaders.TENANT, TENANT_1)
          .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
          .contentType(ContentType.JSON)
          .body(en.encode())
          .post("/print/entries")
          .then()
          .statusCode(204);
    }

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("items", hasSize(greaterThanOrEqualTo(3)))
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(3)));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("limit", 0)
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("items", hasSize(0))
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(3)));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("query", "type=\"SINGLE\"")
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("items", hasSize(greaterThanOrEqualTo(2)))
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(2)));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("query", "type=\"BATCH\"")
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("items", hasSize(1))
        .body("resultInfo.totalRecords", is(1));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("query", "type=\"SINGLE\" and created > " + ZonedDateTime.now()
            .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().minusHours(2))
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("items", hasSize(greaterThanOrEqualTo(2)))
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(2)));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("query", "type=\"SINGLE\" sortby sortingField created")
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("items", hasSize(greaterThanOrEqualTo(2)))
        .body("items[0].sortingField", is("A3"))
        .body("items[1].sortingField", is("A5"))
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(2)));
  }

  @Test
  public void testDeletePrintEntries() {
    List<String> ids = new ArrayList<>();
    PrintEntry entry = new PrintEntry();
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setType(PrintEntryType.SINGLE);
    for (int i = 0; i < 3; i++) {
      entry.setId(UUID.randomUUID());
      entry.setContent("A" + i);
      entry.setSortingField("A" + (5 - i));
      entry.setType(i % 2 == 0 ? PrintEntryType.SINGLE : PrintEntryType.BATCH);
      JsonObject en = JsonObject.mapFrom(entry);
      ids.add(entry.getId().toString());
      RestAssured.given()
          .header(XOkapiHeaders.TENANT, TENANT_1)
          .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
          .contentType(ContentType.JSON)
          .body(en.encode())
          .post("/print/entries")
          .then()
          .statusCode(204);
    }

    int count = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("limit", "100")
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(3)))
        .extract()
        .jsonPath()
        .getInt("resultInfo.totalRecords");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("ids", "")
        .delete("/print/entries")
        .then()
        .statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("limit", "100")
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(count)));

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("ids", String.join(",", ids))
        .delete("/print/entries")
        .then()
        .statusCode(204);

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .queryParam("limit", "100")
        .get("/print/entries")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("resultInfo.totalRecords", is(greaterThanOrEqualTo(count - ids.size())));
  }

  @Test
  public void testSaveMailMessage() throws IOException {
    String message = getResourceAsString("mail/mail.json");

    String id = RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permWrite.encode())
        .contentType(ContentType.JSON)
        .body(message)
        .post("/mail")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("id", notNullValue())
        .extract()
        .path("id");

    RestAssured.given()
        .header(XOkapiHeaders.TENANT, TENANT_1)
        .header(XOkapiHeaders.PERMISSIONS, permRead.encode())
        .get("/print/entries/" + id)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("content", notNullValue());
  }

  private String getResourceAsString(String name) throws IOException {
    try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(name)) {
      if (inputStream == null) {
        throw new IOException("Resource not found: " + name);
      }
      return new String(inputStream.readAllBytes());
    }
  }
}
