package com.example.group.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "group_shift_messages",
        uniqueConstraints = @UniqueConstraint(name = "uniq_chat_slot", columnNames = {"chat_id", "slot_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupShiftMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "message_id", nullable = false)
    private Integer messageId;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "morning_post")
    private boolean morningPost;

    @Column(name = "evening_post")
    private boolean eveningPost;
}
