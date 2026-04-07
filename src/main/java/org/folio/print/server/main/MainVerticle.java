package org.folio.print.server.main;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.jackson.DatabindCodec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.okapi.common.Config;
import org.folio.okapi.common.ModuleVersionReporter;
import org.folio.print.server.service.PrintService;
import org.folio.tlib.RouterCreator;
import org.folio.tlib.api.HealthApi;
import org.folio.tlib.api.Tenant2Api;
import org.folio.tlib.postgres.TenantPgPool;

public class MainVerticle extends AbstractVerticle {
  final Logger log = LogManager.getLogger(MainVerticle.class);

  @Override
  public void start(Promise<Void> promise) {
    TenantPgPool.setModule("mod-batch-print");
    ModuleVersionReporter m = new ModuleVersionReporter("org.folio/mod-batch-print");
    log.info("Starting {} {} {}", m.getModule(), m.getVersion(), m.getCommitId());
    final int port = Integer.parseInt(
        Config.getSysConf("http.port", "port", "8081", config()));
    log.info("Listening on port {}", port);

    var printServiceService = new PrintService();

    RouterCreator[] routerCreators = {
        printServiceService,
        new Tenant2Api(printServiceService),
        new HealthApi()
    };

    RouterCreator.mountAll(vertx, routerCreators, "mod-batch-print")
        .compose(router -> {
          HttpServerOptions so = new HttpServerOptions()
              .setCompressionSupported(true)
              .setDecompressionSupported(true)
              .setHandle100ContinueAutomatically(true);
          return vertx.createHttpServer(so)
              .requestHandler(router)
              .listen(port).mapEmpty();
        })
        .onComplete(x -> promise.handle(x.mapEmpty()));
    configureObjectMapper();
  }

  private void configureObjectMapper() {
    JavaTimeModule javaTimeModule = new JavaTimeModule();
    javaTimeModule.addDeserializer(LocalDateTime.class,
        new LocalDateTimeDeserializer(DateTimeFormatter.ISO_DATE_TIME));
    DatabindCodec.mapper().registerModule(javaTimeModule)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    DatabindCodec.mapper().registerModule(javaTimeModule)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
  }

  @Override
  public void stop(Promise<Void> promise) {
    TenantPgPool.closeAll()
        .onComplete(promise);
  }
}
