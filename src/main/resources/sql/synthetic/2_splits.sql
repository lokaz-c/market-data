-- SYNTHETIC DATA. About one symbol in ten gets one split somewhere in the middle of its history:
-- usually a 2-, 3- or 4-for-1, sometimes a 1-for-10 reverse split. Run setseed() first for repeatable output.
-- Parameters: :years, :endDate
INSERT INTO splits (symbol_id, ex_date, old_rate, new_rate, source)
SELECT id,
       CAST(:endDate AS date) - (180 + floor(r_date * (365 * :years - 360)))::int,
       CASE WHEN r_kind < 0.85 THEN 1 ELSE 10 END,
       CASE WHEN r_kind < 0.85 THEN 2 + floor(r_kind / 0.85 * 3) ELSE 1 END,
       'synthetic'
FROM (
    SELECT id, random() AS r_pick, random() AS r_date, random() AS r_kind
    FROM symbols
    WHERE source = 'synthetic'
      -- Only symbols whose bars are about to be generated, so a re-run never adds a split to existing bars.
      AND NOT EXISTS (SELECT 1 FROM bars b WHERE b.symbol_id = symbols.id)
      AND NOT EXISTS (SELECT 1 FROM splits x WHERE x.symbol_id = symbols.id)
    ORDER BY id
) s
WHERE r_pick < 0.10
ON CONFLICT (symbol_id, ex_date) DO NOTHING
