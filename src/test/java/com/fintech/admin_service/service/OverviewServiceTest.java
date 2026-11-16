package com.fintech.admin_service.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fintech.admin_service.config.AdminProperties;
import com.fintech.admin_service.downstream.Downstream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverviewServiceTest {

    @Test
    void percentChange() {
        assertThat(OverviewService.percentChange(30L, 20L)).isEqualTo(50.0);
        assertThat(OverviewService.percentChange(10L, 0L)).isNull();
        assertThat(OverviewService.percentChange(null, 5L)).isNull();
    }

    @Test
    void signupSeriesIsAlwaysThirtyDaysAndZeroFilled() {
        var series = OverviewService.signupSeries(Map.of("signupsByDay", List.of()));
        assertThat(series).hasSize(30);
        assertThat(series).allSatisfy(p -> assertThat(p.get("value")).isEqualTo(0L));
    }

    @Test
    void anUnreachableSourceGivesNullsNotInventedNumbers() {
        var props = new AdminProperties(new AdminProperties.Downstream("http://127.0.0.1:1", "http://127.0.0.1:1",
                Duration.ofMillis(300)), "");
        var overview = new OverviewService(new Downstream(props, "t", "")).overview();
        assertThat(overview).containsEntry("sources", Map.of("users", false, "billing", false));
        assertThat(((Map<?, ?>) overview.get("totals")).get("users")).isNull();
        assertThat((List<?>) overview.get("signups")).isEmpty();
    }
}
