package com.example.group.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(
        name = "inn",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_inn_value", columnNames = {"value"}),
                @UniqueConstraint(name = "uk_inn_user",  columnNames = {"user_id"})
        }
)
@Getter
@Setter
public class Inn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String value;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;
}