package org.folio.print.server.service;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.openapi.contract.OpenAPIContract;
import io.vertx.openapi.contract.Operation;
import io.vertx.openapi.contract.Parameter;
import io.vertx.openapi.validation.RequestParameter;
import io.vertx.openapi.validation.ValidatedRequest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.util.Hex;
import org.folio.okapi.common.HttpResponse;
import org.folio.print.server.data.Message;
import org.folio.print.server.data.PrintEntry;
import org.folio.print.server.data.PrintEntryType;
import org.folio.print.server.storage.EntryException;
import org.folio.print.server.storage.NotFoundException;
import org.folio.print.server.storage.PrintStorage;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.TenantInitHooks;
import org.folio.tlib.util.TenantUtil;

public class PrintService implements RouterCreator, TenantInitHooks {

  public static final int BODY_LIMIT = 67108864; // 64 Mb

  private static final Logger log = LogManager.getLogger(PrintService.class);

  @Override
  public Future<Router> createRouter(Vertx vertx) {
    return OpenAPIContract.from(vertx, "openapi/batchPrint.yaml")
        .map(contract -> {
          JsonObject rawContract = contract.getRawContract();
          var routerBuilder = RouterBuilder.create(vertx, contract);
          handlers(routerBuilder, rawContract);
          var router = routerBuilder.createRouter();
          router.route("/*").subRouter(routerBuilder.createRouter());
          return router;
        });
  }

  private void failureHandler(RoutingContext ctx) {
    commonError(ctx, ctx.failure(), ctx.statusCode());
  }

  void commonError(RoutingContext ctx, Throwable cause) {
    commonError(ctx, cause, 500);
  }

  void commonError(RoutingContext ctx, Throwable cause, int defaultCode) {
    log.debug("commonError");
    if (cause == null) {
      HttpResponse.responseError(ctx, defaultCode,
          HttpResponseStatus.valueOf(defaultCode).reasonPhrase());
    } else if (cause instanceof NotFoundException) {
      HttpResponse.responseError(ctx, 404, cause.getMessage());
    } else if (cause instanceof EntryException) {
      HttpResponse.responseError(ctx, 400, cause.getMessage());
    } else {
      HttpResponse.responseError(ctx, defaultCode, cause.getMessage()
          + (cause.getCause() != null ? " (" + cause.getCause().getMessage() + ")" : ""));
    }
  }

  /**
   * The Open API 3.0 parser in Vert.x 5.0 appears to only forward query parameters that are
   * actually provided by the client, ignoring if the spec declares defaults. Additionally,
   * if the API cannot use the automatic validation of the spec in vert.x 5 for other reasons,
   * the defaults will be ignored as well. Thus looking up the defaults programmatically.
   */
  static void getParameterDefaults(RoutingContext ctx) {
    Operation operation = (Operation) ctx.currentRoute().metadata().get("openApiOperation");
    if (operation != null) {
      for (Parameter specParameter : operation.getParameters()) {
        String specName = specParameter.getName();
        Object specDefault = specParameter.getSchema().get("default");
        if (specDefault != null && !ctx.request().params().contains(specName)) {
          ctx.request().params().add(specName, specDefault.toString());
        }
      }
    }
  }

  /**
   * Custom programmed check for the provision of mandatory properties in request bodies.
   * Compares top level properties of the JSON request body with list of required
   * (top level) properties per the Open API contract for the operation.
   * This is for APIs that seemingly cannot have their Open API schema automatically
   * validated with Vert.x 5.0 for various reasons.
   * @param ctx Routing context on which the mandatory properties are registered
   * @param rawContract JsonObject containing the Open API spec.
   */
  static Future<Void> validateMandatoryProperties(RoutingContext ctx, JsonObject rawContract) {
    Operation operation = (Operation) ctx.currentRoute().metadata().get("openApiOperation");
    String method = ctx.request().method().name().toLowerCase();
    JsonObject na = new JsonObject();
    JsonArray requiredByContract =
        rawContract.getJsonObject("paths").getJsonObject(operation.getOpenAPIPath())
        .getJsonObject(method, na).getJsonObject("requestBody", na)
        .getJsonObject("content", na).getJsonObject("application/json", na)
        .getJsonObject("schema", na).getJsonArray("required", new JsonArray());
    JsonObject requestBody = ctx.body().asJsonObject();
    for (Object o : requiredByContract) {
      if (!requestBody.containsKey(o.toString())) {
        return Future.failedFuture(
            new EntryException("provided object should contain property " + o));
      }
    }
    return Future.succeededFuture();
  }

  /**
   * Custom programmed -- but not comprehensive -- check for the provision of mandatory
   * parameters in the request.
   * This is for APIs that seemingly cannot have their Open API schema automatically
   * validated with Vert.x 5.0 for various reasons.
   * Only aims to validate according to the specific needs present in the APIs at this point.
   * @param ctx Routing context on which the mandatory properties are registered
   * @param rawContract JsonObject containing the Open API spec.
   */
  static Future<Void> validateMandatoryParameters(RoutingContext ctx, JsonObject rawContract) {
    Operation operation = (Operation) ctx.currentRoute().metadata().get("openApiOperation");
    String method = ctx.request().method().name().toLowerCase();
    JsonArray declaredForPath = rawContract.getJsonObject("paths")
        .getJsonObject(operation.getOpenAPIPath())
        .getJsonArray("parameters", new JsonArray());
    JsonArray declaredForMethod = rawContract.getJsonObject("paths")
        .getJsonObject(operation.getOpenAPIPath())
        .getJsonObject(method)
        .getJsonArray("parameters", new JsonArray());

    for (Object o : declaredForPath.addAll(declaredForMethod)) {
      JsonObject declared = (JsonObject) o;
      Set<String> provided = ctx.request().headers().names();
      provided.addAll(ctx.request().params().names());
      if (declared.getBoolean("required") && !provided.contains(declared.getString("name"))) {
        return Future.failedFuture(
            new EntryException("Missing parameter " + declared.getString("name")));
      }
    }
    return Future.succeededFuture();
  }

  private void handlers(RouterBuilder routerBuilder, JsonObject rawContract) {
    routerBuilder.getRoute("getPrintEntries")
        .setDoValidation(false)
        .addHandler(ctx -> {
          getParameterDefaults(ctx);
          validateMandatoryParameters(ctx, rawContract)
              .compose(na -> getPrintEntries(ctx))
              .onFailure(cause -> commonError(ctx, cause));
        })
        .addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("deletePrintEntries")
        .addHandler(ctx -> {
          deletePrintEntries(ctx)
              .onFailure(cause -> commonError(ctx, cause));
        })
        .addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("postPrintEntry")
        .addHandler(BodyHandler.create().setBodyLimit(BODY_LIMIT))
        .setDoValidation(false)
        .addHandler(ctx -> {
          getParameterDefaults(ctx);
          validateMandatoryParameters(ctx, rawContract)
              .compose(na -> validateMandatoryProperties(ctx, rawContract))
              .compose(na -> postPrintEntry(ctx))
                .onFailure(cause -> commonError(ctx, cause));
        }).addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("getPrintEntry")
        .addHandler(ctx -> {
          getPrintEntry(ctx)
                  .onFailure(cause -> commonError(ctx, cause));
        })
        .addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("deletePrintEntry")
        .addHandler(ctx -> {
          deletePrintEntry(ctx)
                  .onFailure(cause -> commonError(ctx, cause));
        })
        .addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("updatePrintEntry")
        .addHandler(ctx -> {
          getParameterDefaults(ctx);
          updatePrintEntry(ctx)
                  .onFailure(cause -> commonError(ctx, cause));
        })
        .addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("saveMail").setDoValidation(true)
        .addHandler(ctx -> {
          getParameterDefaults(ctx);
          saveMail(ctx)
                  .onFailure(cause -> commonError(ctx, cause));
        })
        .addFailureHandler(this::failureHandler);

    routerBuilder.getRoute("createBatch")
        .addHandler(BatchCreationService::process)
        .addFailureHandler(this::failureHandler);
  }

  public static PrintStorage create(RoutingContext ctx) {
    return new PrintStorage(ctx.vertx(), TenantUtil.tenant(ctx));
  }

  Future<Void> postPrintEntry(RoutingContext ctx) {
    log.info("postPrintEntry:: create entry");
    JsonObject json = ctx.body().asJsonObject();
    PrintStorage storage = create(ctx);
    PrintEntry entry = json.mapTo(PrintEntry.class);
    log.info("postPrintEntry:: entry with type {}, sorting field{}",
        entry.getType(), entry.getSortingField());
    return storage.createEntry(entry)
        .map(entity -> {
          ctx.response().setStatusCode(204);
          ctx.response().end();
          return null;
        });
  }

  Future<Void> deletePrintEntries(RoutingContext ctx) {
    PrintStorage storage = create(ctx);
    ValidatedRequest request = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    RequestParameter idsParameter = request.getQuery().get("ids");
    String ids = idsParameter != null ? idsParameter.getString() : "";
    log.info("deletePrintEntries:: delete entries by ids: {}", ids);
    List<UUID> uuids = Arrays.stream(ids.split(","))
        .filter(id -> id != null && !id.isBlank())
        .map(UUID::fromString)
        .toList();
    return storage.deleteEntries(uuids)
        .map(r -> {
          ctx.response().setStatusCode(204);
          ctx.response().end();
          return null;
        });
  }

  Future<Void> saveMail(RoutingContext ctx) {
    log.info("saveMail:: create single entry");
    final PrintStorage storage = create(ctx);
    ValidatedRequest request = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    Message message = request.getBody().getJsonObject().mapTo(Message.class);
    PrintEntry entry = new PrintEntry();
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setSortingField(message.getTo());
    entry.setContent(Hex.getString(PdfService.createPdfFile(message.getBody())));
    log.info("saveMail:: entry with type {}, sorting field{}",
        entry.getType(), entry.getSortingField());
    return storage.createEntry(entry)
        .map(entity -> {
          HttpResponse.responseJson(ctx,200)
              .end(new JsonObject().put("id", entry.getId()).encode());
          return null;
        });
  }

  Future<Void> getPrintEntry(RoutingContext ctx) {
    PrintStorage storage = create(ctx);
    ValidatedRequest request = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    String id = request.getPathParameters().get("id").getString();
    log.info("getPrintEntry:: get single entry by id: {}", id);
    return storage.getEntry(UUID.fromString(id))
        .map(entity -> {
          HttpResponse.responseJson(ctx, 200)
              .end(JsonObject.mapFrom(entity).encode());
          return null;
        });
  }

  Future<Void> deletePrintEntry(RoutingContext ctx) {
    PrintStorage printStorage = create(ctx);
    ValidatedRequest request = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    String id = request.getPathParameters().get("id").getString();
    log.info("deletePrintEntry:: delete single entry by id: {}", id);
    return printStorage.deleteEntry(UUID.fromString(id))
        .map(res -> {
          ctx.response().setStatusCode(204);
          ctx.response().end();
          return null;
        });
  }

  Future<Void> updatePrintEntry(RoutingContext ctx) {
    PrintStorage printStorage = create(ctx);
    ValidatedRequest request = ctx.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST);
    RequestParameter body = request.getBody();
    PrintEntry entry = body.getJsonObject().mapTo(PrintEntry.class);
    UUID id  = UUID.fromString(request.getPathParameters().get("id").getString());
    log.info("updatePrintEntry:: update single entry by id: {}", id);
    if (!id.equals(entry.getId())) {
      return Future.failedFuture(new EntryException("id mismatch"));
    }
    return printStorage.updateEntry(entry)
        .map(entity -> {
          ctx.response().setStatusCode(204);
          ctx.response().end();
          return null;
        });
  }

  Future<Void> getPrintEntries(RoutingContext ctx) {
    PrintStorage storage = create(ctx);
    String query = ctx.request().params().get("query");
    int limit = Integer.parseInt(ctx.request().params().get("limit"));
    int offset = Integer.parseInt(ctx.request().params().get("offset"));
    log.info("getPrintEntries:: get entries by query: {}, limit {}, offset {}",
        query, limit, offset);
    return storage.getEntries(ctx.response(), query, offset, limit);
  }

  @Override
  public Future<Void> postInit(Vertx vertx, String tenant, JsonObject tenantAttributes) {
    if (!tenantAttributes.containsKey("module_to")) {
      return Future.succeededFuture(); // doing nothing for disable
    }
    PrintStorage storage = new PrintStorage(vertx, tenant);
    return storage.init();
  }

  @Override
  public Future<Void> preInit(Vertx vertx, String tenant, JsonObject tenantAttributes) {
    return Future.succeededFuture();
  }
}
