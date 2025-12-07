package com.example.group.model;


import lombok.Getter;
import jakarta.persistence.*;
import lombok.Setter;

import java.time.LocalDateTime;



@Entity @Table(name="slots")
@Getter @Setter
public class Slot {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional=false, fetch=FetchType.LAZY)
  private Place place;

  @Column(nullable=false)
  private LocalDateTime startTime;   // <<< было ZonedDateTime

  @Column(nullable=false)
  private LocalDateTime endTime;     // <<< было ZonedDateTime

  @Column(nullable=false)
  private Integer capacity;

  @Enumerated(EnumType.STRING)
  private SlotStatus status = SlotStatus.READY;

  public enum SlotStatus { READY, RESERVED, COMPLETED }
}


