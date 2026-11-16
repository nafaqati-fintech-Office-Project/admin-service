package com.fintech.admin_service.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminAccessFilterTest {

    @Test
    void adminRoleIsRecognisedWithOrWithoutPrefixAndInLists() {
        assertThat(AdminAccessFilter.isAdmin("ADMIN")).isTrue();
        assertThat(AdminAccessFilter.isAdmin("USER, ROLE_ADMIN")).isTrue();
        assertThat(AdminAccessFilter.isAdmin("USER")).isFalse();
        assertThat(AdminAccessFilter.isAdmin("ADMINISTRATOR")).isFalse();
        assertThat(AdminAccessFilter.isAdmin(null)).isFalse();
    }

    @Test
    void projectionKeepsOnlyTheNamedFields() {
        var rows = List.<Map<String, Object>>of(Map.of("id", "1", "status", "ACTIVE", "email", "a@b.com", "firstName", "Ali"));
        assertThat(Projection.pickAll(rows, "id", "status")).containsExactly(Map.of("id", "1", "status", "ACTIVE"));
        assertThat(Projection.pickAll(null, "id")).isEmpty();
    }
}
