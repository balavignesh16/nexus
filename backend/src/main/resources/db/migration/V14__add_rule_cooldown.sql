-- Phase 13: optional per-rule cooldown. Nullable, no default beyond SQL's
-- implicit NULL - every rule persisted before this migration reads back as
-- cooldownSeconds = null, i.e. "no cooldown, current behavior," exactly
-- matching Rule.cooldownSeconds' documented semantics. No backfill needed
-- or performed.
ALTER TABLE rules ADD COLUMN cooldown_seconds INTEGER;
