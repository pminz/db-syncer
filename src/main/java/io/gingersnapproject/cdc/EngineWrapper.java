package io.gingersnapproject.cdc;

import static io.debezium.relational.HistorizedRelationalDatabaseConnectorConfig.SCHEMA_HISTORY;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.gingersnapproject.cdc.cache.CacheService;
import io.gingersnapproject.cdc.chain.EventProcessingChain;
import io.gingersnapproject.cdc.chain.EventProcessingChainFactory;
import io.gingersnapproject.cdc.configuration.Backend;
import io.gingersnapproject.cdc.configuration.Connector;
import io.gingersnapproject.cdc.configuration.Database;
import io.gingersnapproject.cdc.configuration.Rule;
import io.gingersnapproject.cdc.connector.DatabaseProvider;
import io.gingersnapproject.cdc.consumer.BatchConsumer;
import io.gingersnapproject.cdc.event.NotificationManager;
import io.gingersnapproject.cdc.remote.RemoteOffsetStore;
import io.gingersnapproject.cdc.remote.RemoteSchemaHistory;
import io.gingersnapproject.cdc.translation.ColumnJsonTranslator;
import io.gingersnapproject.cdc.translation.ColumnStringTranslator;
import io.gingersnapproject.cdc.translation.IdentityTranslator;
import io.gingersnapproject.cdc.translation.JsonTranslator;
import io.gingersnapproject.cdc.translation.PrependJsonTranslator;
import io.gingersnapproject.cdc.translation.PrependStringTranslator;

import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;

public class EngineWrapper {

   private static final ExecutorService executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2), runnable ->
         new Thread(runnable, "engine"));
   private final String name;
   private final CacheService cacheService;
   private final Rule.SingleRule rule;
   private final Properties properties;
   private final NotificationManager eventing;
   private volatile DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;

   private EngineWrapper(String name, Rule.SingleRule rule, Properties properties, CacheService cacheService,
                         NotificationManager eventing) {
      this.name = name;
      this.cacheService = cacheService;
      this.rule = rule;
      this.eventing = eventing;
      this.properties = properties;
   }

   public EngineWrapper(String name, Rule.SingleRule rule, CacheService cacheService, NotificationManager eventing) {
      this(name, rule, defaultProperties(name, rule), cacheService, eventing);
   }

   private static Properties defaultProperties(String name, Rule.SingleRule rule) {
      Properties props = new Properties();
      props.setProperty("name", "engine");

      Connector connector = rule.connector();
      Database database = rule.database();
      // Required property
      props.setProperty("topic.prefix", name);

      // MySQL information
      props.setProperty("database.hostname", database.hostname());
      props.setProperty("database.port", String.valueOf(database.port()));
      props.setProperty("database.user", database.user());
      props.setProperty("database.password", database.password());
      props.setProperty("database.server.name", "gingersnap-eager");
      props.setProperty("snapshot.mode", "initial"); // Behavior when offset not available.

      // Additional configuration
      props.setProperty("tombstones.on.delete", "false"); // Emit single event on delete. Doc says it should be true when using Kafka.
      props.setProperty("converter.schemas.enable", "true"); // Include schema in events, we use to retrieve the key.

      Backend backend = rule.backend();
      String uri = backend.uri().toString();
      props.setProperty(RemoteOffsetStore.URI_CACHE, uri);
      props.setProperty(RemoteOffsetStore.TOPIC_NAME, name);
      props.setProperty("offset.storage", RemoteOffsetStore.class.getCanonicalName());
      props.setProperty("offset.flush.interval.ms", "60000");
      props.setProperty(RemoteSchemaHistory.URI_CACHE, uri);
      props.setProperty(RemoteSchemaHistory.TOPIC_NAME, name);
      props.setProperty(SCHEMA_HISTORY.name(), RemoteSchemaHistory.class.getCanonicalName());

      DatabaseProvider provider = DatabaseProvider.valueOf(connector.connector());
      props.putAll(provider.databaseProperties(connector, database));

      return props;
   }

   public void start() {
      CacheBackend c = createCacheBackend(name, rule);
      if (c != null) {
         EventProcessingChain chain = EventProcessingChainFactory.create(rule, c);
         this.engine = DebeziumEngine.create(Connect.class)
               .using(properties)
               .using(this.getClass().getClassLoader())
               .notifying(new BatchConsumer(this, chain, executor))
               .using(new DebeziumEngine.ConnectorCallback() {
                  @Override
                  public void taskStarted() {
                     eventing.connectorStarted(name);
                  }

                  @Override
                  public void taskStopped() {
                     eventing.connectorStopped(name);
                  }
               })
               .using((success, message, error) -> {
                  if (error != null) eventing.connectorFailed(name, error);
               })
               .build();
         executor.submit(engine);
      }
   }

   public void stop() throws IOException {
      engine.close();
      engine = null;
      cacheService.stop(rule.backend().uri());
   }

   public void notifyError(Throwable t) {
      eventing.connectorFailed(name, t);
   }

   public void shutdownCacheService() {
       cacheService.shutdown(rule.backend().uri());
   }

   public CompletionStage<Boolean> cacheServiceAvailable() {
      return cacheService.reconnect(rule.backend().uri());
   }

   public String getName() {
      return name;
   }

   private CacheBackend createCacheBackend(String name, Rule.SingleRule rule) {
      return cacheService.backendForRule(name, rule);
   }
}
