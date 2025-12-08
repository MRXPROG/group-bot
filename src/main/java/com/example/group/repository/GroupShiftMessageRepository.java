package com.example.group.repository;

import com.example.group.model.GroupShiftMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface GroupShiftMessageRepository extends JpaRepository<GroupShiftMessage, Long> {

    Optional<GroupShiftMessage> findFirstByChatIdAndSlotIdOrderByPostedAtDesc(Long chatId, Long slotId);

    List<GroupShiftMessage> findAllBySlotId(Long slotId);

    List<GroupShiftMessage> findAllByChatId(Long chatId);

    List<GroupShiftMessage> findAllByPostedAtBetweenAndMorningPostIsTrue(LocalDateTime start, LocalDateTime end);
}