package com.example.group.model;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "admins")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Admin {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private Long chatId;

    @Column(nullable = false, unique = true)
    private Long telegramUserId;
}
