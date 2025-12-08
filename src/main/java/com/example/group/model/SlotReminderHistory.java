package com.example.group.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "slot_reminder_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlotReminderHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long slotId;

    /** тип напоминания: 24, 12, 6 */
    private Integer hours;

    private LocalDateTime sentAt;
}
