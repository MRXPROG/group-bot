package com.example.group.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "group_shift_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupShiftMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long chatId;
    private Integer messageId;
    private Long slotId;

    private LocalDateTime postedAt;

    private boolean morningPost;
    private boolean eveningPost;
}
