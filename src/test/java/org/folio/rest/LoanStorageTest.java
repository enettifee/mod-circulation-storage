package org.folio.rest;

import io.vertx.core.json.JsonObject;
import org.folio.rest.support.HttpClient;
import org.folio.rest.support.JsonResponse;
import org.folio.rest.support.ResponseHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;

public class LoanStorageTest {

  private static HttpClient client = new HttpClient(StorageTestSuite.getVertx());

  @Before
  public void beforeEach()
    throws InterruptedException,
    ExecutionException,
    TimeoutException,
    MalformedURLException {

    StorageTestSuite.deleteAll(loanStorageUrl());
  }
  @After
  public void checkIdsAfterEach()
    throws InterruptedException, ExecutionException, TimeoutException {

    StorageTestSuite.checkForMismatchedIDs("loan");
  }

  @Test
  public void canCreateALoan() throws MalformedURLException,
    InterruptedException, ExecutionException, TimeoutException {

    UUID id = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    JsonObject loanRequest = loan(id, itemId, userId);

    CompletableFuture<JsonResponse> createCompleted = new CompletableFuture();

    client.post(loanStorageUrl(), loanRequest, StorageTestSuite.TENANT_ID,
      ResponseHandler.json(createCompleted));

    JsonResponse response = createCompleted.get(5, TimeUnit.SECONDS);

    assertThat(String.format("Failed to create loan: %s", response.getBody()),
      response.getStatusCode(), is(HttpURLConnection.HTTP_CREATED));

    JsonObject loan = response.getJson();

    assertThat("id does not match",
      loan.getString("id"), is(id.toString()));

    assertThat("user id does not match",
      loan.getString("userId"), is(userId.toString()));

    assertThat("item id does not match",
      loan.getString("itemId"), is(itemId.toString()));
  }

  @Test
  public void canGetALoanById() throws MalformedURLException,
    InterruptedException, ExecutionException, TimeoutException {

    UUID id = UUID.randomUUID();
    UUID itemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    JsonObject loanRequest = loan(id, itemId, userId);

    createLoan(loanRequest);

    JsonResponse getResponse = getById(id);

    assertThat(String.format("Failed to get loan: %s", getResponse.getBody()),
      getResponse.getStatusCode(), is(HttpURLConnection.HTTP_OK));

    JsonObject loan = getResponse.getJson();

    assertThat("id does not match",
      loan.getString("id"), is(id.toString()));

    assertThat("user id does not match",
      loan.getString("userId"), is(userId.toString()));

    assertThat("item id does not match",
      loan.getString("itemId"), is(itemId.toString()));
  }

  @Test
  public void loanNotFoundForUnknownId() throws MalformedURLException,
    InterruptedException, ExecutionException, TimeoutException {

    JsonResponse getResponse = getById(UUID.randomUUID());

    assertThat(getResponse.getStatusCode(), is(HttpURLConnection.HTTP_NOT_FOUND));
  }

  private JsonResponse getById(UUID id)
    throws MalformedURLException, InterruptedException,
    ExecutionException, TimeoutException {

    URL getInstanceUrl = loanStorageUrl(String.format("/%s", id));

    CompletableFuture<JsonResponse> getCompleted = new CompletableFuture();

    client.get(getInstanceUrl, StorageTestSuite.TENANT_ID,
      ResponseHandler.json(getCompleted));

    return getCompleted.get(5, TimeUnit.SECONDS);
  }


  private JsonResponse createLoan(JsonObject loanRequest)
    throws MalformedURLException, InterruptedException,
    ExecutionException, TimeoutException {

    CompletableFuture<JsonResponse> createCompleted = new CompletableFuture();

    client.post(loanStorageUrl(), loanRequest, StorageTestSuite.TENANT_ID,
      ResponseHandler.json(createCompleted));

    JsonResponse response = createCompleted.get(5, TimeUnit.SECONDS);

    assertThat(String.format("Failed to create loan: %s", response.getBody()),
      response.getStatusCode(), is(HttpURLConnection.HTTP_CREATED));

    return response;
  }

  private JsonObject loan(UUID id, UUID itemId, UUID userId) {
    return new JsonObject()
      .put("id", id.toString())
      .put("userId", userId.toString())
      .put("itemId", itemId.toString());
  }

  private static URL loanStorageUrl() throws MalformedURLException {
    return loanStorageUrl("");
  }

  private static URL loanStorageUrl(String subPath)
    throws MalformedURLException {

    return StorageTestSuite.storageUrl("/loan-storage/loans" + subPath);
  }
}
