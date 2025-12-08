ALTER TABLE group_shift_messages
    ADD CONSTRAINT uniq_chat_slot UNIQUE (chat_id, slot_id);
