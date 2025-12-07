package com.example.group.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="users")
@Getter @Setter
public class User {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @Column(nullable=false, unique = true)
    private Long telegramId;

    private String username;
    private String firstName;
    private String lastName;
    private String phone;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private Inn inn;
}

