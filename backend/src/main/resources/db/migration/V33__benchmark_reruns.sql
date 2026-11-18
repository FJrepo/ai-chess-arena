-- V31 benchmarks use the pinned v2 rules, with chat and hints stored on the owning row.
UPDATE tournaments
SET benchmark_definition = jsonb_set(benchmark_definition, '{promptVersion}', to_jsonb(
    'v2' ||
    CASE chat_mode WHEN 'OFF' THEN '-silent' WHEN 'MANDATORY' THEN '-chat-required' ELSE '' END ||
    CASE WHEN legal_move_hints THEN '' ELSE '-no-hints' END
))
WHERE benchmark_definition IS NOT NULL AND NOT (benchmark_definition ? 'promptVersion');
