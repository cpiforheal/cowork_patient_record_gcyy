ALTER TABLE pre_ai_encounters
  ADD COLUMN care_direction VARCHAR(24) NOT NULL DEFAULT 'ANORECTAL' AFTER inventory_care_type;

UPDATE pre_ai_encounters SET care_direction = 'ANORECTAL'
WHERE care_direction IS NULL OR care_direction = '';

CREATE INDEX idx_pre_ai_encounters_care_direction
  ON pre_ai_encounters (care_direction, created_at);
