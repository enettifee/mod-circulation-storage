package org.folio.rest.api;

import static io.vertx.core.json.JsonObject.mapFrom;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.awaitility.Awaitility.waitAtMost;
import static org.folio.kafka.services.KafkaEnvironmentProperties.environment;
import static org.folio.kafka.services.KafkaEnvironmentProperties.host;
import static org.folio.kafka.services.KafkaEnvironmentProperties.port;
import static org.folio.rest.api.StorageTestSuite.TENANT_ID;
import static org.folio.rest.api.StorageTestSuite.getVertx;
import static org.folio.rest.support.builders.RequestRequestBuilder.OPEN_NOT_YET_FILLED;
import static org.folio.rest.tools.utils.ModuleName.getModuleName;
import static org.folio.rest.tools.utils.ModuleName.getModuleVersion;
import static org.folio.service.event.InventoryEventType.INVENTORY_ITEM_UPDATED;
import static org.hamcrest.CoreMatchers.equalTo;

import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.folio.rest.jaxrs.model.CallNumberComponents;
import org.folio.rest.jaxrs.model.SearchIndex;
import org.folio.rest.support.ApiTests;
import org.folio.rest.support.builders.ItemBuilder;
import org.folio.rest.support.builders.ItemBuilder.ItemCallNumberComponents;
import org.folio.rest.support.builders.RequestItemSummary;
import org.folio.rest.support.builders.RequestRequestBuilder;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.vertx.core.json.JsonObject;
import io.vertx.kafka.admin.KafkaAdminClient;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.converters.Nullable;
import lombok.SneakyThrows;

@RunWith(JUnitParamsRunner.class)
public class EventConsumerVerticleTest extends ApiTests {

  private static final String REQUEST_ID = "5d2bc53d-db13-4a51-a5a6-160a703706f1";
  private static final String REQUEST_STORAGE_URL = "/request-storage/requests";
  private static final String KAFKA_SERVER_URL = String.format("%s:%s", host(), port());
  private final static String DEFAULT_PICKUP_SERVICE_POINT_NAME = "Circ Desk 1";
  private static final String INVENTORY_ITEM_TOPIC = String.format(
    "%s.%s.inventory.item", environment(), TENANT_ID);

  private static KafkaProducer<String, JsonObject> producer;
  private static KafkaAdminClient adminClient;

  @BeforeClass
  public static void setUpClass() {
    producer = createProducer();
    adminClient = createAdminClient();
  }

  @AfterClass
  public static void tearDownClass() {
    waitFor(
      producer.close()
        .compose(v -> adminClient.close())
    );
  }

  @After
  public void afterEach() {
    truncateTable("request");
  }

  @Parameters({
    "OLD_PFX | NEW_PFX | OLD_CN | OLD_CN | OLD_SFX | OLD_SFX | OLD_SO | OLD_SO", // prefix changed
    "OLD_PFX | null    | OLD_CN | OLD_CN | OLD_SFX | OLD_SFX | OLD_SO | OLD_SO", // prefix removed
    "null    | NEW_PFX | OLD_CN | OLD_CN | OLD_SFX | OLD_SFX | OLD_SO | OLD_SO", // prefix added

    "OLD_PFX | OLD_PFX | OLD_CN | NEW_CN | OLD_SFX | OLD_SFX | OLD_SO | OLD_SO", // call number changed
    "OLD_PFX | OLD_PFX | OLD_CN | null   | OLD_SFX | OLD_SFX | OLD_SO | OLD_SO", // call number removed
    "OLD_PFX | OLD_PFX | null   | NEW_CN | OLD_SFX | OLD_SFX | OLD_SO | OLD_SO", // call number added

    "OLD_PFX | OLD_PFX | OLD_CN | OLD_CN | OLD_SFX | NEW_SFX | OLD_SO | OLD_SO", // suffix changed
    "OLD_PFX | OLD_PFX | OLD_CN | OLD_CN | OLD_SFX | null    | OLD_SO | OLD_SO", // suffix removed
    "OLD_PFX | OLD_PFX | OLD_CN | OLD_CN | null    | NEW_SFX | OLD_SO | OLD_SO", // suffix added

    "OLD_PFX | OLD_PFX | OLD_CN | OLD_CN | OLD_SFX | OLD_SFX | OLD_SO | NEW_SO", // shelving order changed
    "OLD_PFX | OLD_PFX | OLD_CN | OLD_CN | OLD_SFX | OLD_SFX | OLD_SO | null  ", // shelving order removed
    "OLD_PFX | OLD_PFX | OLD_CN | OLD_CN | OLD_SFX | OLD_SFX | null   | NEW_SO", // shelving order added

    "OLD_PFX | NEW_PFX | OLD_CN | NEW_CN | OLD_SFX | NEW_SFX | OLD_SO | NEW_SO", // everything changed
    "OLD_PFX | null    | OLD_CN | null   | OLD_SFX | null    | OLD_SO | null  ", // everything removed
    "null    | NEW_PFX | null   | NEW_CN | null    | NEW_SFX | null   | NEW_SO", // everything added
  })
  @Test
  public void requestSearchIndexIsUpdatedWhenItemIsUpdated(
    @Nullable String oldPrefix, @Nullable String newPrefix,
    @Nullable String oldCallNumber, @Nullable String newCallNumber,
    @Nullable String oldSuffix, @Nullable String newSuffix,
    @Nullable String oldShelvingOrder, @Nullable String newShelvingOrder) {


    JsonObject oldItem = buildItem(oldPrefix, oldCallNumber, oldSuffix, oldShelvingOrder);
    JsonObject newItem = buildItem(newPrefix, newCallNumber, newSuffix, newShelvingOrder);
    SearchIndex oldIndex = buildSearchIndex(oldPrefix, oldCallNumber, oldSuffix, oldShelvingOrder);
    SearchIndex newIndex = buildSearchIndex(newPrefix, newCallNumber, newSuffix, newShelvingOrder);

    createRequest(buildRequest(REQUEST_ID, oldItem));
    verifyRequestSearchIndex(REQUEST_ID, oldIndex);

    publishItemUpdateEvent(oldItem, newItem);
    verifyRequestSearchIndex(REQUEST_ID, newIndex);
  }

  @Test
  public void requestSearchIndexIsNotUpdatedWhenEventContainsNoRelevantChanges() {
    String prefix = "prefix";
    String callNumber = "callNumber";
    String suffix = "suffix";
    String shelvingOrder = "shelvingOrder";

    JsonObject oldItem = buildItem(prefix, callNumber, suffix, shelvingOrder);
    JsonObject newItem = oldItem.copy().put("barcode", "new-barcode"); // irrelevant change
    SearchIndex searchIndex = buildSearchIndex(prefix, callNumber, suffix, shelvingOrder);

    createRequest(buildRequest(REQUEST_ID, oldItem));
    verifyRequestSearchIndex(REQUEST_ID, searchIndex);

    int initialOffset = getOffsetForItemUpdateEvents();
    publishItemUpdateEvent(oldItem, newItem);
    waitUntilValueIsIncreased(initialOffset, EventConsumerVerticleTest::getOffsetForItemUpdateEvents);
    verifyRequestSearchIndex(REQUEST_ID, searchIndex);
  }

  @Test
  public void requestSearchIndexIsNotUpdatedWhenRequestAndEventAreForDifferentItems() {
    String prefix = "prefix";
    String callNumber = "callNumber";
    String suffix = "suffix";
    String shelvingOrder = "shelvingOrder";

    JsonObject oldItem = buildItem(prefix, callNumber, suffix, shelvingOrder);
    JsonObject newItem = oldItem.copy().put("effectiveShelvingOrder", "new-order"); // relevant change
    SearchIndex searchIndex = buildSearchIndex(prefix, callNumber, suffix, shelvingOrder);

    JsonObject request = buildRequest(REQUEST_ID, oldItem).put("itemId", randomId());
    createRequest(request);
    verifyRequestSearchIndex(REQUEST_ID, searchIndex);

    int initialOffset = getOffsetForItemUpdateEvents();
    publishItemUpdateEvent(oldItem, newItem);
    waitUntilValueIsIncreased(initialOffset, EventConsumerVerticleTest::getOffsetForItemUpdateEvents);
    verifyRequestSearchIndex(REQUEST_ID, searchIndex);
  }

  private static JsonObject buildItem(String prefix, String callNumber, String suffix,
    String shelvingOrder) {

    return new ItemBuilder()
      .withId(randomId())
      .withHoldingsRecordId(randomId())
      .withStatus("Paged")
      .withEffectiveShelvingOrder(shelvingOrder)
      .withCallNumberComponents(new ItemCallNumberComponents()
        .withPrefix(prefix)
        .withCallNumber(callNumber)
        .withSuffix(suffix))
      .create();
  }

  private static SearchIndex buildSearchIndex(String callNumberPrefix, String callNumber,
    String callNumberSuffix, String shelvingOrder) {

    return new SearchIndex()
      .withPickupServicePointName(DEFAULT_PICKUP_SERVICE_POINT_NAME)
      .withShelvingOrder(shelvingOrder)
      .withCallNumberComponents(new CallNumberComponents()
        .withPrefix(callNumberPrefix)
        .withCallNumber(callNumber)
        .withSuffix(callNumberSuffix));
  }

  @SneakyThrows
  private JsonObject createRequest(JsonObject request) {
    return createEntity(request, requestStorageUrl()).getJson();
  }

  private static URL requestStorageUrl() {
    return requestStorageUrl("");
  }

  @SneakyThrows
  private static URL requestStorageUrl(String subPath) {
    return StorageTestSuite.storageUrl(REQUEST_STORAGE_URL + subPath);
  }

  private JsonObject getRequest(String requestId) {
    return getById(requestStorageUrl("/" + requestId));
  }

  private static KafkaProducer<String, JsonObject> createProducer() {
    Properties config = new Properties();
    config.put(BOOTSTRAP_SERVERS_CONFIG, KAFKA_SERVER_URL);
    config.put(ACKS_CONFIG, "1");

    return KafkaProducer.create(getVertx(), config, String.class, JsonObject.class);
  }

  private void publishItemUpdateEvent(JsonObject oldItem, JsonObject newItem) {
    publishEvent(INVENTORY_ITEM_TOPIC, buildUpdateEvent(oldItem, newItem));
  }

  private void publishEvent(String topic, JsonObject eventPayload) {
    var record = KafkaProducerRecord.create(topic, "test-key", eventPayload);
    record.addHeader("X-Okapi-Tenant", TENANT_ID);
    waitFor(producer.write(record));
  }

  private static JsonObject buildUpdateEvent(JsonObject oldVersion, JsonObject newVersion) {
    return new JsonObject()
      .put("tenant", TENANT_ID)
      .put("type", "UPDATE")
      .put("old", oldVersion)
      .put("new", newVersion);
  }

  private JsonObject verifyRequestSearchIndex(String requestId, SearchIndex searchIndex) {
    return waitAtMost(60, SECONDS)
      .until(() -> getRequestSearchIndex(requestId), equalTo(mapFrom(searchIndex)));
  }

  private JsonObject getRequestSearchIndex(String requestId) {
    return getRequest(requestId).getJsonObject("searchIndex");
  }

  private static JsonObject buildRequest(String requestId, JsonObject item) {
    SearchIndex searchIndex = new SearchIndex()
      .withPickupServicePointName(DEFAULT_PICKUP_SERVICE_POINT_NAME)
      .withShelvingOrder(item.getString("effectiveShelvingOrder"));

    JsonObject callNumberComponents = item.getJsonObject("effectiveCallNumberComponents");
    if (callNumberComponents != null) {
      searchIndex = searchIndex.withCallNumberComponents(new CallNumberComponents()
        .withCallNumber(callNumberComponents.getString("callNumber"))
        .withPrefix(callNumberComponents.getString("prefix"))
        .withSuffix(callNumberComponents.getString("suffix")));
    }

    return new RequestRequestBuilder()
      .withId(UUID.fromString(requestId))
      .page()
      .toHoldShelf()
      .withRequestDate(new DateTime(2017, 7, 22, 10, 22, 54, DateTimeZone.UTC))
      .withItemId(UUID.fromString(item.getString("id")))
      .withRequesterId(UUID.randomUUID())
      .withRequestLevel("Item")
      .withItem(new RequestItemSummary("Nod", "565578437802"))
      .withHoldingsRecordId(UUID.fromString(item.getString("holdingsRecordId")))
      .withInstanceId(UUID.randomUUID())
      .withRequester("Jones", "Stuart", "Anthony", "6837502674015")
      .withStatus(OPEN_NOT_YET_FILLED)
      .withPosition(1)
      .withPickupServicePointId(UUID.randomUUID())
      .withSearchIndex(searchIndex)
      .create();
  }

  private static Integer getOffsetForItemUpdateEvents() {
    return getOffset(INVENTORY_ITEM_TOPIC, buildConsumerGroupId(INVENTORY_ITEM_UPDATED.name()));
  }

  private static String buildConsumerGroupId(String eventType) {
    return String.format("%s.%s-%s", eventType, getModuleName().replace("_", "-"), getModuleVersion());
  }

  private static KafkaAdminClient createAdminClient() {
    Map<String, String> config = Map.of(BOOTSTRAP_SERVERS_CONFIG, KAFKA_SERVER_URL);
    return KafkaAdminClient.create(getVertx(), config);
  }

  private static int waitUntilValueIsIncreased(int previousValue, Callable<Integer> valueSupplier) {
    return waitAtMost(60, SECONDS)
      .until(valueSupplier, newValue -> newValue > previousValue);
  }

  private static int getOffset(String topic, String consumerGroupId) {
    return waitFor(
      adminClient.listConsumerGroupOffsets(consumerGroupId)
        .map(partitions -> Optional.ofNullable(partitions.get(new TopicPartition(topic, 0)))
          .map(OffsetAndMetadata::getOffset)
          .map(Long::intValue)
          .orElse(0)) // if topic does not exist yet
    );
  }
}