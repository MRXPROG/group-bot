package com.example.group.repository;

import com.example.group.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    List<User> findTop10ByScorePointsEmptyOrderByScorePointsDesc();
}
