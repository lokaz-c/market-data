INSERT INTO splits AS s (symbol_id, ex_date, old_rate, new_rate, source)
VALUES (:symbolId, :exDate, :oldRate, :newRate, 'alpaca')
ON CONFLICT (symbol_id, ex_date) DO UPDATE
SET old_rate = excluded.old_rate,
    new_rate = excluded.new_rate,
    source   = excluded.source
WHERE (s.old_rate, s.new_rate, s.source) IS DISTINCT FROM (excluded.old_rate, excluded.new_rate, excluded.source)
