-- Jobs rendered before the RENDERED state existed (DESIGN.md §5.1) stayed in
-- RENDERING forever, and clips whose render task ran out of attempts were never
-- marked FAILED. Bring existing rows in line with the new rules.

-- Clips whose RENDER_CLIP failed for good
UPDATE clip
SET state = 'FAILED', updated_at = NOW()
WHERE state IN ('PLANNED', 'RENDERING')
  AND EXISTS (SELECT 1 FROM task t WHERE t.clip_id = clip.id AND t.state = 'FAILED');

-- Jobs whose clips are all in a final render state
UPDATE video_job j
SET state = 'RENDERED', updated_at = NOW()
WHERE j.state = 'RENDERING'
  AND EXISTS (SELECT 1 FROM clip c WHERE c.video_job_id = j.id)
  AND NOT EXISTS (
        SELECT 1 FROM clip c
        WHERE c.video_job_id = j.id
          AND c.state NOT IN ('RENDERED', 'FAILED', 'CANCELLED'));
