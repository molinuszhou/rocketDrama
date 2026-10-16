BEGIN;

-- v1720 created the original category CHECK. Upgrade existing production databases
-- so high-level UI interactions can be queried separately from playback and rewards.
DO $$
DECLARE
    existing_constraint TEXT;
BEGIN
    SELECT conname
    INTO existing_constraint
    FROM pg_constraint
    WHERE conrelid = 'public.user_behavior_events'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%event_category%'
    LIMIT 1;

    IF existing_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE user_behavior_events DROP CONSTRAINT %I', existing_constraint);
    END IF;
END $$;

ALTER TABLE user_behavior_events
    ADD CONSTRAINT user_behavior_events_event_category_check
    CHECK (event_category IN (
        'APP', 'SDK', 'ONBOARDING', 'PLAYBACK', 'AD', 'NETWORK', 'REWARDS', 'UI'
    ));

COMMIT;
