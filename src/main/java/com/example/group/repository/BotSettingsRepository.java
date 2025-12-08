package com.example.group.repository;

import com.example.group.model.BotSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotSettingsRepository extends JpaRepository<BotSettings, Long> {
}
