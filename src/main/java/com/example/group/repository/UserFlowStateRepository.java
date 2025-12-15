package com.example.group.repository;

import com.example.group.model.UserFlowState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserFlowStateRepository extends JpaRepository<UserFlowState, Long> {
    Optional<UserFlowState> findByUserId(Long userId);

    Optional<UserFlowState> findByChatIdAndBotMessageId(Long chatId, Integer botMessageId);

    @Query("select f from UserFlowState f where f.expiresAt < :now")
    List<UserFlowState> findExpired(@Param("now") LocalDateTime now);
}
