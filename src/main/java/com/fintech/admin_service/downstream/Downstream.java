package com.fintech.admin_service.downstream;

import com.fintech.admin_service.config.AdminProperties;
import com.fintech.admin_service.domain.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls to the other services. Reads use the read-only /internal/admin/** projections (ids, states, counts: no personal data).
 * Admin actions on requests are forwarded to user-service's own admin endpoints with the admin's bearer token, so user-service
 * still decides (ROLE_ADMIN) and this service records the outcome in the audit log.
 */
@Component
public class Downstream {

    public static final String CALLER = "admin-service";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };

    private final RestClient users;
    private final RestClient billing;
    private final RestClient usersApi;

    public Downstream(AdminProperties properties, @Value("${internal.api-token:}") String sharedToken,
                      @Value("${INTERNAL_CALLER_SECRET:}") String ownSecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.downstream().timeout());
        factory.setReadTimeout(properties.downstream().timeout());
        String token = ownSecret != null && !ownSecret.isBlank() ? ownSecret : sharedToken == null ? "" : sharedToken;
        this.users = internalClient(properties.downstream().userServiceUrl(), factory, token);
        this.billing = internalClient(properties.downstream().billingServiceUrl(), factory, token);
        this.usersApi = RestClient.builder().baseUrl(properties.downstream().userServiceUrl()).requestFactory(factory).build();
    }

    private static RestClient internalClient(String baseUrl, SimpleClientHttpRequestFactory factory, String token) {
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Internal-Service", CALLER).defaultHeader("X-Internal-Token", token).build();
    }

    public Map<String, Object> userStats() {
        return users.get().uri("/internal/admin/stats").retrieve().body(MAP);
    }

    public Map<String, Object> userList(int page, int size) {
        return users.get().uri("/internal/admin/users?page={p}&size={s}", page, size).retrieve().body(MAP);
    }

    public List<Map<String, Object>> emailChanges() {
        return users.get().uri("/internal/admin/email-change-requests").retrieve().body(LIST);
    }

    public List<Map<String, Object>> deletionRequests() {
        return users.get().uri("/internal/admin/deletion-requests").retrieve().body(LIST);
    }

    public Map<String, Object> billingStats() {
        return billing.get().uri("/internal/admin/stats").retrieve().body(MAP);
    }

    /** Runs an action in user-service as the admin. Maps the answer to a status without passing the body through. */
    public void emailChangeConfirm(UUID id, String authorization) {
        forward("/api/admin/email-change-requests/{id}/confirm", id, authorization);
    }

    public void deletionAction(UUID id, String action, String authorization) {
        forward("/api/admin/deletion-requests/{id}/" + action, id, authorization);
    }

    private void forward(String path, UUID id, String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required");
        }
        try {
            usersApi.post().uri(path, id).header("Authorization", authorization).retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                throw new ApiException(status, "REJECTED_BY_SOURCE", "The request was rejected (" + status.value() + ")");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_ERROR", "user-service could not complete the request");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", "user-service is not reachable");
        }
    }
}
