-- Every split of one symbol, oldest first: one range scan of splits_pkey (symbol_id, ex_date).
SELECT ex_date, old_rate, new_rate
FROM splits
WHERE symbol_id = :symbolId
ORDER BY ex_date
