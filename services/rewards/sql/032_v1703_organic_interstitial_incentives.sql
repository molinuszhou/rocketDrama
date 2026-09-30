BEGIN;

-- Organic A surface settles non-unlock incentive actions only after this actual
-- interstitial display has been recorded. Keep all historical placement values.
ALTER TABLE interstitial_events DROP CONSTRAINT IF EXISTS interstitial_events_placement_id_check;
ALTER TABLE interstitial_events
    ADD CONSTRAINT interstitial_events_placement_id_check
    CHECK (placement_id IN (
        'int_timer',
        'int_pause',
        'int_next_episode_unlock',
        'int_pause_native',
        'int_reward_claim',
        'int_episode_midroll',
        'int_seek',
        'int_speed',
        'int_rewarded_fallback',
        'int_incentive_action',
        'int_drama_switch'
    ));

COMMENT ON TABLE rewarded_fallback_grants IS
    'Idempotent grants after a verified interstitial: legacy rewarded fallback and Organic A incentive interstitials.';

COMMIT;
