package com.example.group.repository;

import com.example.group.model.SlotReminderHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlotReminderHistoryRepository extends JpaRepository<SlotReminderHistory, Long> {

    boolean existsBySlotIdAndHours(Long slotId, Integer hours);
}
