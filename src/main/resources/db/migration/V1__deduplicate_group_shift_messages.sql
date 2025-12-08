DELETE FROM group_shift_messages g
USING (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY chat_id, slot_id
               ORDER BY posted_at DESC NULLS LAST, id DESC
           ) AS rn
    FROM group_shift_messages
) dup
WHERE g.id = dup.id
  AND dup.rn > 1;
