package org.folio.print.server.service;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.validation.RequestParameter;
import io.vertx.ext.web.validation.RequestParameters;
import io.vertx.ext.web.validation.ValidationHandler;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.util.Hex;
import org.folio.okapi.common.HttpResponse;
import org.folio.okapi.common.XOkapiHeaders;
import org.folio.print.server.data.Message;
import org.folio.print.server.data.PrintEntry;
import org.folio.print.server.data.PrintEntryType;
import org.folio.print.server.storage.ForbiddenException;
import org.folio.print.server.storage.NotFoundException;
import org.folio.print.server.storage.PrintStorage;
import org.folio.print.server.storage.UserException;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.TenantInitHooks;

public class PrintService implements RouterCreator, TenantInitHooks {

  public static final int BODY_LIMIT = 67108864; // 64 Mb

  private static final Logger log = LogManager.getLogger(PrintService.class);

  @Override
  public Future<Router> createRouter(Vertx vertx) {
    return RouterBuilder.create(vertx, "openapi/batchPrint.yaml")
        .map(routerBuilder -> {
          // https://vertx.io/docs/vertx-web/java/#_limiting_body_size
          routerBuilder.rootHandler(BodyHandler.create().setBodyLimit(BODY_LIMIT));
          handlers(routerBuilder);
          Router router = Router.router(vertx);
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
    } else if (cause instanceof ForbiddenException) {
      HttpResponse.responseError(ctx, 403, cause.getMessage());
    } else if (cause instanceof NotFoundException) {
      HttpResponse.responseError(ctx, 404, cause.getMessage());
    } else if (cause instanceof UserException) {
      HttpResponse.responseError(ctx, 400, cause.getMessage());
    } else {
      HttpResponse.responseError(ctx, defaultCode, cause.getMessage());
    }
  }

  private void handlers(RouterBuilder routerBuilder) {
    routerBuilder
        .operation("getPrintEntries")
        .handler(ctx -> getPrintEntries(ctx)
            .onFailure(cause -> commonError(ctx, cause))
        )
        .failureHandler(this::failureHandler);

    routerBuilder
        .operation("postPrintEntry")
        .handler(ctx -> postPrintEntry(ctx)
            .onFailure(cause -> commonError(ctx, cause))
        )
        .failureHandler(this::failureHandler);
    routerBuilder
        .operation("getPrintEntry")
        .handler(ctx -> getPrintEntry(ctx)
            .onFailure(cause -> commonError(ctx, cause))
        )
        .failureHandler(this::failureHandler);
    routerBuilder
        .operation("deletePrintEntry")
        .handler(ctx -> deletePrintEntry(ctx)
            .onFailure(cause -> commonError(ctx, cause))
        )
        .failureHandler(this::failureHandler);

    routerBuilder
        .operation("updatePrintEntry")
        .handler(ctx -> updatePrintEntry(ctx)
            .onFailure(cause -> commonError(ctx, cause))
        )
        .failureHandler(this::failureHandler);

    routerBuilder
        .operation("saveMail")
        .handler(ctx -> saveMail(ctx)
            .onFailure(cause -> commonError(ctx, cause))
        )
        .failureHandler(this::failureHandler);

    routerBuilder
        .operation("createBatch")
        .handler(BatchCreationService::process)
        .failureHandler(this::failureHandler);
  }

  static PrintStorage createFromParams(Vertx vertx, RequestParameters params) {
    // get tenant
    RequestParameter tenantParameter = params.headerParameter(XOkapiHeaders.TENANT);

    return new PrintStorage(vertx, tenantParameter.getString());
  }

  public static PrintStorage create(RoutingContext ctx) {
    return createFromParams(ctx.vertx(), ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY));
  }

  Future<Void> postPrintEntry(RoutingContext ctx) {
    PrintStorage storage = create(ctx);
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    RequestParameter body = params.body();
    PrintEntry entry = body.getJsonObject().mapTo(PrintEntry.class);
    return storage.createEntry(entry)
        .map(entity -> {
          ctx.response().setStatusCode(204);
          ctx.response().end();
          return null;
        });
  }

  Future<Void> saveMail(RoutingContext ctx) {
    final PrintStorage storage = create(ctx);
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    RequestParameter body = params.body();
    Message message = body.getJsonObject().mapTo(Message.class);
    PrintEntry entry = new PrintEntry();
    entry.setId(UUID.randomUUID());
    entry.setType(PrintEntryType.SINGLE);
    entry.setCreated(ZonedDateTime.now().withZoneSameInstant(ZoneOffset.UTC));
    entry.setSortingField(message.getTo());
    entry.setContent(Hex.getString(PdfService.createPdfFile(message.getBody())));
    return storage.createEntry(entry)
        .map(entity -> {
          ctx.response().setStatusCode(HttpResponseStatus.OK.code());
          ctx.response().end(new JsonObject().put("id", entry.getId()).encode());
          return null;
        });
  }

  Future<Void> getPrintEntry(RoutingContext ctx) {
    PrintStorage storage = create(ctx);
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    String id  = params.pathParameter("id").getString();
    return storage.getEntry(UUID.fromString(id))
        .map(entity -> {
          HttpResponse.responseJson(ctx, 200)
              .end(JsonObject.mapFrom(entity).encode());
          return null;
        });
  }

  Future<Void> deletePrintEntry(RoutingContext ctx) {
    PrintStorage printStorage = create(ctx);
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    String id  = params.pathParameter("id").getString();
    return printStorage.deleteEntry(UUID.fromString(id))
        .map(res -> {
          ctx.response().setStatusCode(204);
          ctx.response().end();
          return null;
        });
  }

  Future<Void> updatePrintEntry(RoutingContext ctx) {
    PrintStorage printStorage = create(ctx);
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    RequestParameter body = params.body();
    PrintEntry entry = body.getJsonObject().mapTo(PrintEntry.class);
    UUID id  = UUID.fromString(params.pathParameter("id").getString());
    if (!id.equals(entry.getId())) {
      return Future.failedFuture(new UserException("id mismatch"));
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
    RequestParameters params = ctx.get(ValidationHandler.REQUEST_CONTEXT_KEY);
    RequestParameter queryParameter = params.queryParameter("query");
    String query = queryParameter != null ? queryParameter.getString() : null;
    RequestParameter limitParameter = params.queryParameter("limit");
    int limit = limitParameter != null ? limitParameter.getInteger() : 10;
    RequestParameter offsetParameter = params.queryParameter("offset");
    int offset = offsetParameter != null ? offsetParameter.getInteger() : 0;
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
