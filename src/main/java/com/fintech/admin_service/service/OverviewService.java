package com.fintech.admin_service.service;

import com.fintech.admin_service.downstream.Downstream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the dashboard numbers from the services that own them. Aggregates only. A source that is down or does not have the
 * figure yet yields null / empty (and sources.<name>=false) instead of an invented number: the panel shows "not available".
 */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final Downstream downstream;

    public OverviewService(Downstream downstream) {
        this.downstream = downstream;
    }

    /** {@code authorization}: the admin's bearer token, needed for auth-service's activity counts (null skips them). */
    public Map<String, Object> overview(String authorization) {
        Map<String, Object> userStats = attempt("user-service", downstream::userStats);
        Map<String, Object> logins = authorization == null ? null
                : attempt("auth-service", () -> downstream.loginActivity(authorization));
        Map<String, Object> billing = attempt("billing-service", downstream::billingStats);
        Map<String, Object> family = attempt("family-service", downstream::familyStats);
        Map<String, Object> activity = attempt("transaction-service", downstream::transactionStats);

        Long users = number(userStats, "total");
        Long premium = number(billing, "activeSubscriptions");

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("users", users);
        // "active" = signed in or refreshed a session in the last 30 days (auth-service). If auth-service cannot answer, the
        // fallback is "recorded at least one transaction" (transaction-service).
        totals.put("activeUsers30d", logins != null ? number(logins, "active30d") : number(activity, "activeAccounts30d"));
        totals.put("activeUsers7d", number(logins, "active7d"));
        totals.put("activeUsers1d", number(logins, "active1d"));
        totals.put("premium", premium);
        totals.put("families", number(family, "families"));

        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("users", userStats == null ? null : percentChange(number(userStats, "last30"), number(userStats, "previous30")));
        deltas.put("activeUsers30d", logins != null ? percentChange(number(logins, "active30d"), number(logins, "activePrevious30d"))
                : activity == null ? null : percentChange(number(activity, "activeAccounts30d"), number(activity, "activeAccountsPrevious30d")));
        deltas.put("premium", null);
        deltas.put("families", family == null ? null : percentChange(number(family, "last30"), number(family, "previous30")));

        List<Map<String, Object>> plans = new ArrayList<>();
        if (users != null && premium != null) {
            plans.add(Map.of("label", "Free", "count", Math.max(users - premium, 0)));
            plans.add(Map.of("label", "Premium", "count", premium));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("deltas", deltas);
        out.put("signups", userStats == null ? List.of() : signupSeries(userStats));
        out.put("dailyActive", logins == null ? List.of() : daySeries(logins, "activeByDay"));
        out.put("planBreakdown", plans);
        out.put("platformBreakdown", List.of());
        out.put("featureAdoption", List.of());
        out.put("sources", Map.of("users", userStats != null, "billing", billing != null, "families", family != null,
                "activity", activity != null, "logins", logins != null));
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> billing() {
        Map<String, Object> stats = attempt("billing-service", downstream::billingStats);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("activeSubscriptions", number(stats, "activeSubscriptions"));
        totals.put("pastDue", number(stats, "pastDue"));
        totals.put("churn30d", stats == null ? null : stats.get("churn30d"));
        totals.put("failedPayments7d", number(stats, "failedPayments7d"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("byPlan", stats == null ? List.of() : relabel((List<Map<String, Object>>) stats.get("byInterval")));
        out.put("byProvider", stats == null ? List.of() : stats.get("byProvider"));
        out.put("growthIndex", List.of());
        out.put("available", stats != null);
        return out;
    }

    private static List<Map<String, Object>> relabel(List<Map<String, Object>> intervals) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : intervals == null ? List.<Map<String, Object>>of() : intervals) {
            String label = "MONTHLY".equals(row.get("label")) ? "Premium monthly"
                    : "YEARLY".equals(row.get("label")) ? "Premium yearly" : String.valueOf(row.get("label"));
            out.add(Map.of("label", label, "count", row.get("count")));
        }
        return out;
    }

    static List<Map<String, Object>> signupSeries(Map<String, Object> userStats) {
        return daySeries(userStats, "signupsByDay");
    }

    /** The last 30 days, zero-filled, as {date: "MM-dd", value}, from rows {date: yyyy-MM-dd, count}. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> daySeries(Map<String, Object> source, String key) {
        Map<String, Long> byDay = new HashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) source.getOrDefault(key, List.of())) {
            byDay.put((String) row.get("date"), ((Number) row.get("count")).longValue());
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 29; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            series.add(Map.of("date", day.format(DateTimeFormatter.ofPattern("MM-dd")),
                    "value", byDay.getOrDefault(day.toString(), 0L)));
        }
        return series;
    }

    static Double percentChange(Long current, Long previous) {
        if (current == null || previous == null || previous == 0) {
            return null;
        }
        return Math.round((current - previous) * 1000.0 / previous) / 10.0;
    }

    private static Long number(Map<String, Object> map, String key) {
        return map != null && map.get(key) instanceof Number n ? n.longValue() : null;
    }

    private static Map<String, Object> attempt(String source, Supplier<Map<String, Object>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("{} is not available for the overview: {}", source, e.getClass().getSimpleName());
            return null;
        }
    }
}
