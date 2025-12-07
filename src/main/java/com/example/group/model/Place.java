package com.example.group.model;

import jakarta.persistence.*;
import lombok.Getter; import lombok.Setter;

@Entity @Table(name="places")
@Getter @Setter
public class Place {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional=false, fetch=FetchType.LAZY)
  private City city;

  @Column(nullable=false)
  private String name;


  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "inn_required", nullable = false)
  private boolean innRequired = false;

  @Column(nullable=false)
  private boolean visible = true;
}

