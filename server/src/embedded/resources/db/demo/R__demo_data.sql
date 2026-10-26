-- Demo data for the `demo` profile (docs/DESIGN.md §13.1): only with
-- --spring.profiles.active=embedded,demo, never in the production jar.
--
-- Everything here is made up: no real videos, channels or accounts. It stays
-- inert on purpose:
--   - the social accounts hold placeholder tokens that aren't even encrypted:
--     only publishing and stats collection read them, and
--     application-demo.properties turns those schedulers off (with the planner,
--     alerts and cleanup), so nothing calls a platform or sends an email. A
--     resend clicked by hand fails on decrypting the token, before any call;
--   - no job in a state the startup recovery or the render trigger picks up
--     (SUBMITTED, VALIDATING, DOWNLOADING, TRANSCRIBING, ANALYZING), no task
--     waiting for a worker and no publication UPLOADING;
--   - published posts have no external id, so no link points at a video
--     that doesn't exist.
-- Dates are relative to now(): the embedded database is created on each start.

-- ── Workers (registered, offline) ───────────────────────────────────────────
INSERT INTO worker (id, name, token_hash, last_capabilities, last_seen_at, created_at) VALUES
('de000000-0000-4000-8000-00000000a001', 'PC de escritorio (Radeon)', 'demo-token-hash-1',
 '{"gpuAvailable": true, "encoders": ["h264_amf", "hevc_amf", "libopenh264"], "whisperBackend": "vulkan", "availableModels": ["large-v3-turbo", "small"], "maxConcurrentTasks": 2}',
 now() - interval '35 minutes', now() - interval '40 days'),
('de000000-0000-4000-8000-00000000a002', 'Portátil (sin GPU)', 'demo-token-hash-2',
 '{"gpuAvailable": false, "encoders": ["libopenh264"], "whisperBackend": "cpu", "availableModels": ["small"], "maxConcurrentTasks": 1}',
 now() - interval '2 days', now() - interval '21 days');

-- ── Video jobs ──────────────────────────────────────────────────────────────
INSERT INTO video_job (id, source_url, source_platform, source_channel, state, language, fps_mode, review_mode,
                       priority, title, category, duration_seconds, source_fps, resolution, rejection_reason,
                       clip_mode, created_at, updated_at) VALUES
('de000000-0000-4000-8000-000000000001', 'https://example.com/videos/entrevista-primer-empleo', 'youtube',
 'Canal de ejemplo', 'COMPLETED', 'es', 'auto', false, 0,
 'Entrevista: cómo conseguir tu primer empleo como desarrollador', 'Tecnología', 3720, 30, '1920x1080', NULL,
 'AUTO', now() - interval '9 days', now() - interval '6 days'),
('de000000-0000-4000-8000-000000000002', 'https://example.com/videos/charla-arquitectura', 'youtube',
 'Canal de ejemplo', 'PUBLISHING', 'es', 'auto', true, 0,
 'Charla: arquitectura de software en la práctica', 'Tecnología', 2940, 60, '2560x1440', NULL,
 'AUTO', now() - interval '3 days', now() - interval '2 hours'),
('de000000-0000-4000-8000-000000000003', 'https://example.com/videos/directo-speedrun', 'twitch',
 'Streamer de ejemplo', 'SCHEDULED', 'es', 'auto', false, 0,
 'Directo: speedrun con comentarios', 'Videojuegos', 6300, 60, '1920x1080', NULL,
 'MANUAL', now() - interval '1 day', now() - interval '20 hours'),
('de000000-0000-4000-8000-000000000004', 'https://example.com/videos/podcast-privado', 'youtube',
 NULL, 'REJECTED', NULL, 'auto', false, 0,
 NULL, NULL, NULL, NULL, NULL, 'El vídeo es privado: solo su autor puede verlo.',
 'AUTO', now() - interval '5 hours', now() - interval '5 hours'),
('de000000-0000-4000-8000-000000000005', 'https://example.com/videos/directo-maraton', 'kick',
 'Streamer de ejemplo', 'REJECTED', NULL, 'auto', false, 0,
 'Maratón de 9 horas', NULL, 32400, 60, '1920x1080', 'Dura 9 h 0 min: el máximo es 2 h.',
 'AUTO', now() - interval '4 days', now() - interval '4 days');

-- ── Clips ───────────────────────────────────────────────────────────────────
INSERT INTO clip (id, video_job_id, clip_index, state, kept_ranges_json, fps, title, description, duration_seconds,
                  overlay_title, section_title, part_index, part_count, created_at, updated_at) VALUES
-- job 1: published everywhere, files already deleted
('de000000-0000-4000-8000-0000000c0101', 'de000000-0000-4000-8000-000000000001', 0, 'CLEANED',
 '[{"startMs": 312000, "endMs": 356500}]', 30,
 'El error que cometen casi todos en su primera entrevista',
 'Lo que de verdad miran en una entrevista técnica. Créditos: Canal de ejemplo. #programación #empleo #desarrollo',
 44, 'El error de la primera entrevista', 'Entrevistas técnicas', NULL, NULL,
 now() - interval '8 days', now() - interval '6 days'),
('de000000-0000-4000-8000-0000000c0102', 'de000000-0000-4000-8000-000000000001', 1, 'CLEANED',
 '[{"startMs": 845000, "endMs": 902000}]', 30,
 'Portfolio o título: qué pesa más (parte 1/2)',
 'Primera parte: por qué un proyecto propio dice más que un certificado. Créditos: Canal de ejemplo. #portfolio #programación',
 57, 'Portfolio o título (1/2)', 'Portfolio', 1, 2,
 now() - interval '8 days', now() - interval '6 days'),
('de000000-0000-4000-8000-0000000c0103', 'de000000-0000-4000-8000-000000000001', 2, 'CLEANED',
 '[{"startMs": 902000, "endMs": 951000}]', 30,
 'Portfolio o título: qué pesa más (parte 2/2)',
 'Segunda parte: cómo presentar tus proyectos. Créditos: Canal de ejemplo. #portfolio #github',
 49, 'Portfolio o título (2/2)', 'Portfolio', 2, 2,
 now() - interval '8 days', now() - interval '6 days'),
-- job 2: half published, half scheduled
('de000000-0000-4000-8000-0000000c0201', 'de000000-0000-4000-8000-000000000002', 0, 'PUBLISHED',
 '[{"startMs": 120000, "endMs": 168000}]', 60,
 'Monolito primero: por qué no empezar con microservicios',
 'La regla que repiten los arquitectos con experiencia. Créditos: Canal de ejemplo. #arquitectura #software',
 48, 'Monolito primero', 'Microservicios', NULL, NULL,
 now() - interval '2 days', now() - interval '1 day'),
('de000000-0000-4000-8000-0000000c0202', 'de000000-0000-4000-8000-000000000002', 1, 'PUBLISHED',
 '[{"startMs": 640000, "endMs": 693000}, {"startMs": 701000, "endMs": 712000}]', 60,
 'Los tests que sí merecen la pena',
 'Menos tests de detalle, más tests de comportamiento. Créditos: Canal de ejemplo. #testing #java',
 64, 'Los tests que merecen la pena', 'Testing', NULL, NULL,
 now() - interval '2 days', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000c0203', 'de000000-0000-4000-8000-000000000002', 2, 'SCHEDULED',
 '[{"startMs": 1210000, "endMs": 1262000}]', 60,
 'Deuda técnica: cuándo pagarla',
 'Cómo decidir qué deuda técnica atacar primero. Créditos: Canal de ejemplo. #deudatecnica #software',
 52, 'Deuda técnica', 'Deuda técnica', NULL, NULL,
 now() - interval '2 days', now() - interval '2 hours'),
('de000000-0000-4000-8000-0000000c0204', 'de000000-0000-4000-8000-000000000002', 3, 'SCHEDULED',
 '[{"startMs": 1880000, "endMs": 1935000}]', 60,
 'Documentar decisiones, no código',
 'Por qué un registro de decisiones vale más que mil comentarios. Créditos: Canal de ejemplo. #documentación',
 55, 'Documentar decisiones', 'Documentación', NULL, NULL,
 now() - interval '2 days', now() - interval '2 hours'),
-- job 3: clips chosen by hand, all scheduled
('de000000-0000-4000-8000-0000000c0301', 'de000000-0000-4000-8000-000000000003', 0, 'SCHEDULED',
 '[{"startMs": 2405000, "endMs": 2441000}]', 60,
 'El salto que ahorra 40 segundos',
 'El truco del nivel 3, explicado en directo. Créditos: Streamer de ejemplo. #speedrun #gaming',
 36, 'El salto de 40 segundos', NULL, NULL, NULL,
 now() - interval '22 hours', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000c0302', 'de000000-0000-4000-8000-000000000003', 1, 'SCHEDULED',
 '[{"startMs": 5120000, "endMs": 5171000}]', 60,
 'Récord personal en el último segundo',
 'La reacción al ver el tiempo final. Créditos: Streamer de ejemplo. #speedrun #récord',
 51, 'Récord en el último segundo', NULL, NULL, NULL,
 now() - interval '22 hours', now() - interval '20 hours');

-- ── Tasks (finished history; nothing waits for a worker) ────────────────────
INSERT INTO task (id, type, state, attempt, assigned_worker_id, video_job_id, clip_id, parameters_json,
                  last_progress_percent, last_progress_phase, last_progress_at, created_at, updated_at) VALUES
('de000000-0000-4000-8000-00000000f101', 'TRANSCRIBE', 'DONE', 1, 'de000000-0000-4000-8000-00000000a001',
 'de000000-0000-4000-8000-000000000001', NULL, '{}', 100, 'done', now() - interval '8 days 20 hours',
 now() - interval '8 days 21 hours', now() - interval '8 days 20 hours'),
('de000000-0000-4000-8000-00000000f102', 'RENDER_CLIP', 'DONE', 1, 'de000000-0000-4000-8000-00000000a001',
 NULL, 'de000000-0000-4000-8000-0000000c0101', '{}', 100, 'done', now() - interval '8 days 18 hours',
 now() - interval '8 days 19 hours', now() - interval '8 days 18 hours'),
('de000000-0000-4000-8000-00000000f103', 'RENDER_CLIP', 'DONE', 2, 'de000000-0000-4000-8000-00000000a002',
 NULL, 'de000000-0000-4000-8000-0000000c0102', '{}', 100, 'done', now() - interval '8 days 17 hours',
 now() - interval '8 days 19 hours', now() - interval '8 days 17 hours'),
('de000000-0000-4000-8000-00000000f104', 'RENDER_CLIP', 'DONE', 1, 'de000000-0000-4000-8000-00000000a001',
 NULL, 'de000000-0000-4000-8000-0000000c0103', '{}', 100, 'done', now() - interval '8 days 18 hours',
 now() - interval '8 days 19 hours', now() - interval '8 days 18 hours'),
('de000000-0000-4000-8000-00000000f201', 'TRANSCRIBE', 'DONE', 1, 'de000000-0000-4000-8000-00000000a001',
 'de000000-0000-4000-8000-000000000002', NULL, '{}', 100, 'done', now() - interval '2 days 22 hours',
 now() - interval '2 days 23 hours', now() - interval '2 days 22 hours');

-- ── Publications ────────────────────────────────────────────────────────────
INSERT INTO scheduled_post (id, clip_id, platform, scheduled_at, state, origin, attempts, published_at,
                            created_at, updated_at) VALUES
('de000000-0000-4000-8000-0000000b0101', 'de000000-0000-4000-8000-0000000c0101', 'YOUTUBE',   now() - interval '7 days 4 hours',  'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '7 days 4 hours',  now() - interval '8 days', now() - interval '7 days'),
('de000000-0000-4000-8000-0000000b0102', 'de000000-0000-4000-8000-0000000c0101', 'INSTAGRAM', now() - interval '7 days 4 hours',  'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '7 days 4 hours',  now() - interval '8 days', now() - interval '7 days'),
('de000000-0000-4000-8000-0000000b0103', 'de000000-0000-4000-8000-0000000c0101', 'TIKTOK',    now() - interval '7 days 4 hours',  'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '7 days 3 hours',  now() - interval '8 days', now() - interval '7 days'),
('de000000-0000-4000-8000-0000000b0201', 'de000000-0000-4000-8000-0000000c0102', 'YOUTUBE',   now() - interval '6 days 21 hours', 'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '6 days 21 hours', now() - interval '8 days', now() - interval '6 days'),
('de000000-0000-4000-8000-0000000b0202', 'de000000-0000-4000-8000-0000000c0102', 'INSTAGRAM', now() - interval '6 days 21 hours', 'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '6 days 21 hours', now() - interval '8 days', now() - interval '6 days'),
('de000000-0000-4000-8000-0000000b0301', 'de000000-0000-4000-8000-0000000c0103', 'YOUTUBE',   now() - interval '6 days 2 hours',  'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '6 days 2 hours',  now() - interval '8 days', now() - interval '6 days'),
('de000000-0000-4000-8000-0000000b0302', 'de000000-0000-4000-8000-0000000c0103', 'INSTAGRAM', now() - interval '6 days 2 hours',  'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '6 days 2 hours',  now() - interval '8 days', now() - interval '6 days'),
('de000000-0000-4000-8000-0000000b0401', 'de000000-0000-4000-8000-0000000c0201', 'YOUTUBE',   now() - interval '1 day 5 hours',   'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '1 day 5 hours',   now() - interval '2 days', now() - interval '1 day'),
('de000000-0000-4000-8000-0000000b0402', 'de000000-0000-4000-8000-0000000c0201', 'INSTAGRAM', now() - interval '1 day 5 hours',   'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '1 day 5 hours',   now() - interval '2 days', now() - interval '1 day'),
('de000000-0000-4000-8000-0000000b0501', 'de000000-0000-4000-8000-0000000c0202', 'YOUTUBE',   now() - interval '20 hours',        'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '20 hours',        now() - interval '2 days', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000b0502', 'de000000-0000-4000-8000-0000000c0202', 'INSTAGRAM', now() - interval '20 hours',        'PUBLISHED', 'CLAUDE_PLAN', 1, now() - interval '20 hours',        now() - interval '2 days', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000b0601', 'de000000-0000-4000-8000-0000000c0203', 'YOUTUBE',   now() + interval '3 hours',         'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '2 hours', now() - interval '2 hours'),
('de000000-0000-4000-8000-0000000b0602', 'de000000-0000-4000-8000-0000000c0203', 'INSTAGRAM', now() + interval '3 hours',         'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '2 hours', now() - interval '2 hours'),
('de000000-0000-4000-8000-0000000b0603', 'de000000-0000-4000-8000-0000000c0203', 'TIKTOK',    now() + interval '3 hours',         'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '2 hours', now() - interval '2 hours'),
('de000000-0000-4000-8000-0000000b0701', 'de000000-0000-4000-8000-0000000c0204', 'YOUTUBE',   now() + interval '1 day 2 hours',   'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '2 hours', now() - interval '2 hours'),
('de000000-0000-4000-8000-0000000b0702', 'de000000-0000-4000-8000-0000000c0204', 'INSTAGRAM', now() + interval '1 day 2 hours',   'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '2 hours', now() - interval '2 hours'),
('de000000-0000-4000-8000-0000000b0801', 'de000000-0000-4000-8000-0000000c0301', 'YOUTUBE',   now() + interval '1 day 6 hours',   'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '20 hours', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000b0802', 'de000000-0000-4000-8000-0000000c0301', 'TIKTOK',    now() + interval '1 day 6 hours',   'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '20 hours', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000b0901', 'de000000-0000-4000-8000-0000000c0302', 'YOUTUBE',   now() + interval '2 days 5 hours',  'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '20 hours', now() - interval '20 hours'),
('de000000-0000-4000-8000-0000000b0902', 'de000000-0000-4000-8000-0000000c0302', 'INSTAGRAM', now() + interval '2 days 5 hours',  'SCHEDULED', 'CLAUDE_PLAN', 0, NULL, now() - interval '20 hours', now() - interval '20 hours');

-- ── Statistics (latest snapshot per published post) ─────────────────────────
INSERT INTO post_stats (id, scheduled_post_id, captured_at, metrics_json) VALUES
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0101', now() - interval '1 hour', '{"views": 18420, "likes": 1310, "comments": 87, "averageViewPercentage": 71.4, "averageViewDurationSeconds": 31.4, "minutesWatched": 9640, "subscribersGained": 64}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0102', now() - interval '1 hour', '{"views": 9730, "likes": 842, "comments": 41}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0201', now() - interval '1 hour', '{"views": 7215, "likes": 498, "comments": 23, "averageViewPercentage": 64.2, "averageViewDurationSeconds": 36.6, "minutesWatched": 4400, "subscribersGained": 19}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0202', now() - interval '1 hour', '{"views": 5120, "likes": 377, "comments": 12}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0301', now() - interval '1 hour', '{"views": 6088, "likes": 402, "comments": 17, "averageViewPercentage": 68.9, "averageViewDurationSeconds": 33.8, "minutesWatched": 3430, "subscribersGained": 15}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0302', now() - interval '1 hour', '{"views": 4310, "likes": 296, "comments": 9}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0401', now() - interval '1 hour', '{"views": 3962, "likes": 287, "comments": 31, "averageViewPercentage": 74.8, "averageViewDurationSeconds": 35.9, "minutesWatched": 2370, "subscribersGained": 22}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0402', now() - interval '1 hour', '{"views": 2875, "likes": 233, "comments": 14}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0501', now() - interval '1 hour', '{"views": 1544, "likes": 121, "comments": 8, "averageViewPercentage": 59.3, "averageViewDurationSeconds": 38.0, "minutesWatched": 978, "subscribersGained": 6}'),
(gen_random_uuid(), 'de000000-0000-4000-8000-0000000b0502', now() - interval '1 hour', '{"views": 1180, "likes": 96, "comments": 5}');

-- ── Activity log ────────────────────────────────────────────────────────────
INSERT INTO log_event (id, level, component, message, context_json, created_at) VALUES
(gen_random_uuid(), 'INFO', 'publicaciones', 'Publicado en YouTube: Los tests que sí merecen la pena', NULL, now() - interval '20 hours'),
(gen_random_uuid(), 'INFO', 'publicaciones', 'Publicado en Instagram: Los tests que sí merecen la pena', NULL, now() - interval '20 hours'),
(gen_random_uuid(), 'INFO', 'publicaciones', 'Plan del día: 7 publicaciones repartidas entre 3 plataformas', NULL, now() - interval '2 hours'),
(gen_random_uuid(), 'INFO', 'tareas', '4 clips renderizados con h264_amf en PC de escritorio (Radeon)', NULL, now() - interval '2 days 20 hours'),
(gen_random_uuid(), 'WARN', 'workers', 'Portátil (sin GPU) se desconectó; su tarea vuelve a la cola', NULL, now() - interval '8 days 18 hours'),
(gen_random_uuid(), 'INFO', 'trabajos', 'Rechazado: el vídeo es privado', NULL, now() - interval '5 hours'),
(gen_random_uuid(), 'INFO', 'trabajos', 'Claude propuso 4 clips en 4 secciones; el servidor los ajustó a pausas naturales', NULL, now() - interval '2 days 21 hours');

-- ── Connected accounts (placeholder tokens, see the header) ─────────────────
INSERT INTO social_account (id, platform, external_account_id, display_name, access_token_encrypted,
                            refresh_token_encrypted, expires_at, connected_at) VALUES
('de000000-0000-4000-8000-00000000c001', 'YOUTUBE',   'demo-youtube',   'Canal de ejemplo',   'demo-placeholder', 'demo-placeholder', now() + interval '50 minutes', now() - interval '60 days'),
('de000000-0000-4000-8000-00000000c002', 'INSTAGRAM', 'demo-instagram', '@canal.de.ejemplo',  'demo-placeholder', NULL,               now() + interval '50 days',    now() - interval '60 days'),
('de000000-0000-4000-8000-00000000c003', 'TIKTOK',    'demo-tiktok',    '@canaldeejemplo',    'demo-placeholder', 'demo-placeholder', now() + interval '20 hours',   now() - interval '60 days');

-- Daily account snapshots for the last 30 days: subscribers and channel views growing
INSERT INTO account_stats (id, social_account_id, captured_at, metrics_json)
SELECT gen_random_uuid(), 'de000000-0000-4000-8000-00000000c001', now() - make_interval(days => d),
       jsonb_build_object('subscribers', 1180 + (30 - d) * 23 + ((30 - d) * (30 - d)) / 3,
                          'views', 402000 + (30 - d) * 5100)
FROM generate_series(0, 30) AS d;
INSERT INTO account_stats (id, social_account_id, captured_at, metrics_json)
SELECT gen_random_uuid(), 'de000000-0000-4000-8000-00000000c002', now() - make_interval(days => d),
       jsonb_build_object('subscribers', 860 + (30 - d) * 15)
FROM generate_series(0, 30) AS d;
