BEGIN;

-- v1.7.1.8: a single SHOWN operation record now includes its observed eCPM.
-- Rebuild the retained raw-event window so historic dashboards use the merged
-- record's eCPM rather than expecting a separate PAID_REVENUE row.
DELETE FROM ad_mediation_daily_metrics
WHERE metric_date >= (CURRENT_DATE - INTERVAL '90 days')::date;

INSERT INTO ad_mediation_daily_metrics (
    metric_date, app_version, logical_placement, topon_placement_id,
    ad_format, ad_network, ad_source_id, currency,
    shown_count, paid_revenue_count, revenue_value
)
SELECT
    client_occurred_at::date,
    app_version,
    logical_placement,
    topon_placement_id,
    ad_format,
    ad_network,
    ad_source_id,
    currency,
    COUNT(*) FILTER (WHERE event_type = 'SHOWN')::BIGINT,
    COUNT(*) FILTER (WHERE ecpm IS NOT NULL)::BIGINT,
    COALESCE(SUM(CASE WHEN ecpm IS NOT NULL THEN ecpm / 1000 ELSE 0 END), 0)
FROM ad_mediation_events
WHERE client_occurred_at >= (CURRENT_DATE - INTERVAL '90 days')
GROUP BY
    client_occurred_at::date,
    app_version,
    logical_placement,
    topon_placement_id,
    ad_format,
    ad_network,
    ad_source_id,
    currency;

COMMIT;
