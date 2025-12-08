package com.example.group.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_first_last", columnNames = {"first_name", "last_name"})
)
@Getter @Setter
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, unique = true)
    private Long telegramId;

    private String username;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String phone;

    private Integer score;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private Inn inn;
}

