package org.folio.rest.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import org.folio.cql2pgjson.CQL2PgJSON;
import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.model.RequestPolicies;
import org.folio.rest.jaxrs.model.RequestPolicy;
import org.folio.rest.jaxrs.resource.RequestPolicyStorage;
import org.folio.rest.persist.MyPgUtil;
import org.folio.rest.persist.PgUtil;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.persist.cql.CQLWrapper;
import org.folio.rest.tools.utils.OutStream;
import org.folio.rest.tools.utils.TenantTool;
import javax.ws.rs.core.Response;

import java.util.Map;
import java.util.UUID;

import static org.folio.rest.impl.Headers.TENANT_HEADER;

public class RequestPoliciesAPI implements RequestPolicyStorage {
  private static final Logger log = LogManager.getLogger();

  private static final String REQUEST_POLICY_TABLE = "request_policy";
  private static final Class<RequestPolicy> REQUEST_POLICY_CLASS = RequestPolicy.class;

  @Validate
  @Override
  public void getRequestPolicyStorageRequestPolicies(int offset, int limit, String query, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PgUtil.get(REQUEST_POLICY_TABLE, REQUEST_POLICY_CLASS, RequestPolicies.class,
        query, offset, limit, okapiHeaders, vertxContext,
        GetRequestPolicyStorageRequestPoliciesResponse.class, asyncResultHandler);
  }

  @Validate
  @Override
  public void postRequestPolicyStorageRequestPolicies(String lang, RequestPolicy entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    // TODO: replace by PgUtil.post once RMB >= 25.0.2 gets released with this fix:
    // https://issues.folio.org/browse/RMB-401 "PgUtil.post: Object or POJO type for entity parameter of respond201WithApplicationJson"
    // https://github.com/folio-org/raml-module-builder/pull/444

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    try {
      PostgresClient postgresClient =
        PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

      vertxContext.runOnContext(v -> {
        try {
          if(entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
          }

          postgresClient.save(REQUEST_POLICY_TABLE, entity.getId(), entity,
            reply -> {
              try {
                if(reply.succeeded()) {
                  OutStream stream = new OutStream();
                  stream.setData(entity);

                  asyncResultHandler.handle(
                    io.vertx.core.Future.succeededFuture(
                      RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
                        .respond201WithApplicationJson(entity,
                          RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse.headersFor201().withLocation(reply.result()))));
                }
                else {
                  asyncResultHandler.handle(
                    io.vertx.core.Future.succeededFuture(
                      RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
                        .respond500WithTextPlain(reply.cause().toString())));
                }
              } catch (Exception e) {
                log.error(e);
                asyncResultHandler.handle(
                  io.vertx.core.Future.succeededFuture(
                    RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
                      .respond500WithTextPlain(e.getMessage())));
              }
            });
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
            RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
              .respond500WithTextPlain(e.getMessage())));
        }
      });
    } catch (Exception e) {
      log.error(e);
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
        RequestPolicyStorage.PostRequestPolicyStorageRequestPoliciesResponse
          .respond500WithTextPlain(e.getMessage())));
    }
  }

  @Validate
  @Override
  public void deleteRequestPolicyStorageRequestPolicies(String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {

    String tenantId = okapiHeaders.get(TENANT_HEADER);

    vertxContext.runOnContext(v -> {
      try {
        PostgresClient postgresClient = PostgresClient.getInstance(
          vertxContext.owner(), TenantTool.calculateTenantId(tenantId));

        CQL2PgJSON cql2pgJson = new CQL2PgJSON("request_policy.jsonb");
        CQLWrapper cql = new CQLWrapper(cql2pgJson, null);

        postgresClient.delete(REQUEST_POLICY_TABLE, cql,
          reply -> asyncResultHandler.handle(Future.succeededFuture(
            DeleteRequestPolicyStorageRequestPoliciesResponse.respond204())));
      }
      catch(Exception e) {
        asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(
          RequestPolicyStorage.DeleteRequestPolicyStorageRequestPoliciesResponse
            .respond500WithTextPlain(e.getMessage())));
      }
    });
  }

  @Validate
  @Override
  public void getRequestPolicyStorageRequestPoliciesByRequestPolicyId(String requestPolicyId, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PgUtil.getById(REQUEST_POLICY_TABLE, REQUEST_POLICY_CLASS, requestPolicyId, okapiHeaders, vertxContext,
        GetRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse.class, asyncResultHandler);
  }

  @Validate
  @Override
  public void putRequestPolicyStorageRequestPoliciesByRequestPolicyId(String requestPolicyId, String lang, RequestPolicy entity, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    // TODO: on insert return 201, not 204
    MyPgUtil.putUpsert204(REQUEST_POLICY_TABLE, entity, requestPolicyId, okapiHeaders, vertxContext,
        PutRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse.class, asyncResultHandler);
  }

  @Validate
  @Override
  public void deleteRequestPolicyStorageRequestPoliciesByRequestPolicyId(String requestPolicyId, String lang, Map<String, String> okapiHeaders, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) {
    PgUtil.deleteById(REQUEST_POLICY_TABLE, requestPolicyId, okapiHeaders, vertxContext,
        DeleteRequestPolicyStorageRequestPoliciesByRequestPolicyIdResponse.class, asyncResultHandler);
  }
}
