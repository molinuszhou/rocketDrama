from __future__ import annotations

from pathlib import Path

import psycopg

from app.config import Settings


def apply_runtime_migrations(settings: Settings) -> None:
    """在 API 启动前补跑可重复执行的增量迁移。

    PostgreSQL 官方镜像仅会在数据卷首次创建时执行 initdb 目录中的 SQL；
    已存在的本地测试账本需由此函数非破坏性地应用后续 schema 版本。
    """
    sql_directory = Path(__file__).resolve().parent.parent / "sql"
    migration_paths = (
        ("002_monetization_v3", sql_directory / "002_monetization_v3.sql"),
        ("003_rewards_v4", sql_directory / "003_rewards_v4.sql"),
        ("004_ad_flow_v5", sql_directory / "004_ad_flow_v5.sql"),
        ("005_random_chest_and_reward_v6", sql_directory / "005_random_chest_and_reward_v6.sql"),
        ("006_rewarded_actions_and_player_v7", sql_directory / "006_rewarded_actions_and_player_v7.sql"),
        ("007_v8_player_reward_polish", sql_directory / "007_v8_player_reward_polish.sql"),
        ("008_v81_ad_fatigue_and_crown_curve", sql_directory / "008_v81_ad_fatigue_and_crown_curve.sql"),
        ("009_v162_reward_control", sql_directory / "009_v162_reward_control.sql"),
        ("010_v163_localized_wallet_and_rewards", sql_directory / "010_v163_localized_wallet_and_rewards.sql"),
        ("011_v163_round_gem_values", sql_directory / "011_v163_round_gem_values.sql"),
        ("012_v163_round_gem_values_v2", sql_directory / "012_v163_round_gem_values_v2.sql"),
        ("013_v163_damping_thresholds_v3", sql_directory / "013_v163_damping_thresholds_v3.sql"),
        ("014_market_wallet_policy_management", sql_directory / "014_market_wallet_policy_management.sql"),
        ("015_app_review_reward", sql_directory / "015_app_review_reward.sql"),
        ("016_crown_road_effective_rounds", sql_directory / "016_crown_road_effective_rounds.sql"),
        ("017_v1635_checkin_200k_five_tier_damping", sql_directory / "017_v1635_checkin_200k_five_tier_damping.sql"),
        ("018_low_frequency_ad_policy", sql_directory / "018_low_frequency_ad_policy.sql"),
        ("019_direct_cash_wallet_tasks_and_ad_cooldowns", sql_directory / "019_direct_cash_wallet_tasks_and_ad_cooldowns.sql"),
        ("020_v1647_random_task_redpackets", sql_directory / "020_v1647_random_task_redpackets.sql"),
        ("021_v1648_six_tier_damping", sql_directory / "021_v1648_six_tier_damping.sql"),
        ("022_v1649_interstitial_schedule_and_usd_default", sql_directory / "022_v1649_interstitial_schedule_and_usd_default.sql"),
        ("023_v1650_episode_reward_claim_prompt_threshold", sql_directory / "023_v1650_episode_reward_claim_prompt_threshold.sql"),
        ("024_v1651_daily_task_tiers", sql_directory / "024_v1651_daily_task_tiers.sql"),
        ("025_v1652_charge_cooldowns_and_crown_points", sql_directory / "025_v1652_charge_cooldowns_and_crown_points.sql"),
        ("026_v1656_player_reward_fallback_and_cooldowns", sql_directory / "026_v1656_player_reward_fallback_and_cooldowns.sql"),
        ("027_ad_mediation_operations_events", sql_directory / "027_ad_mediation_operations_events.sql"),
        ("028_v1657_reward_center_targets", sql_directory / "028_v1657_reward_center_targets.sql"),
        ("029_v1700_300usd_three_day_curve", sql_directory / "029_v1700_300usd_three_day_curve.sql"),
        ("030_v1701_seven_day_cycle_ten_active_boosts", sql_directory / "030_v1701_seven_day_cycle_ten_active_boosts.sql"),
        ("031_v1702_damping_starts_after_290usd", sql_directory / "031_v1702_damping_starts_after_290usd.sql"),
        ("032_v1703_organic_interstitial_incentives", sql_directory / "032_v1703_organic_interstitial_incentives.sql"),
        ("033_v1704_rounded_290usd_curve", sql_directory / "033_v1704_rounded_290usd_curve.sql"),
        ("034_v1705_high_episode_wallet_rewards", sql_directory / "034_v1705_high_episode_wallet_rewards.sql"),
        ("035_v1706_regional_caps_and_precision_damping", sql_directory / "035_v1706_regional_caps_and_precision_damping.sql"),
        ("036_v1707_user_attribution", sql_directory / "036_v1707_user_attribution.sql"),
        ("037_v1708_reporting_delivery_events", sql_directory / "037_v1708_reporting_delivery_events.sql"),
        ("038_v1709_reporting_user_uid", sql_directory / "038_v1709_reporting_user_uid.sql"),
        ("039_v1710_user_geo_country", sql_directory / "039_v1710_user_geo_country.sql"),
        ("040_v1711_withdrawal_accounts", sql_directory / "040_v1711_withdrawal_accounts.sql"),
        ("041_v1712_ad_unavailable_episode_unlocks", sql_directory / "041_v1712_ad_unavailable_episode_unlocks.sql"),
        ("042_v1713_integrated_ad_reporting_targets", sql_directory / "042_v1713_integrated_ad_reporting_targets.sql"),
        ("043_v1714_preserve_legacy_hk_policy", sql_directory / "043_v1714_preserve_legacy_hk_policy.sql"),
        ("044_v1715_admin_auth", sql_directory / "044_v1715_admin_auth.sql"),
        ("045_v1716_withdrawal_reservations", sql_directory / "045_v1716_withdrawal_reservations.sql"),
        ("046_v1717_user_scoped_ledger_idempotency", sql_directory / "046_v1717_user_scoped_ledger_idempotency.sql"),
    )
    with psycopg.connect(settings.database_url, autocommit=True) as connection:
        with connection.cursor() as cursor:
            # Multiple API containers may start together. A transaction-scoped lock cannot
            # span legacy SQL files that contain their own BEGIN/COMMIT, so retain this
            # session lock through the complete ordered sequence.
            cursor.execute("SELECT pg_advisory_lock(hashtext('joyedrama_rewards_runtime_migrations'))")
            try:
                # Each incremental SQL file includes historical compatibility ALTERs. Once
                # marked complete it must not be replayed, or legacy constraints can narrow.
                cursor.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version TEXT PRIMARY KEY,
                        applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """
                )
                for version, sql_path in migration_paths:
                    cursor.execute("SELECT 1 FROM schema_migrations WHERE version = %s", (version,))
                    if cursor.fetchone() is not None:
                        continue
                    if version == "002_monetization_v3":
                        # v3 predates schema_migrations and is mounted directly during init.
                        # Do not replay its old rewarded-placement CHECK on a live ledger.
                        cursor.execute(
                            """
                            SELECT 1 FROM information_schema.columns
                            WHERE table_name = 'reward_policy' AND column_name = 'claim_stride_day0'
                            """
                        )
                        if cursor.fetchone() is not None:
                            cursor.execute(
                                "INSERT INTO schema_migrations (version) VALUES (%s) ON CONFLICT DO NOTHING",
                                (version,),
                            )
                            continue
                    cursor.execute(sql_path.read_text(encoding="utf-8"))
                    cursor.execute("INSERT INTO schema_migrations (version) VALUES (%s) ON CONFLICT DO NOTHING", (version,))
            finally:
                cursor.execute("SELECT pg_advisory_unlock(hashtext('joyedrama_rewards_runtime_migrations'))")
