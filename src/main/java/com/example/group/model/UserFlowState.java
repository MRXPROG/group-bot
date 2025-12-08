package com.example.group.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_flow_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserFlowState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long chatId;

    private String firstName;
    private String lastName;

    /** сообщение пользователя, которое мы должны удалить */
    private Integer userMessageId;

    /** сообщение бота с кнопками */
    private Integer botMessageId;

    private Long slotId;

    private LocalDateTime expiresAt;
}
