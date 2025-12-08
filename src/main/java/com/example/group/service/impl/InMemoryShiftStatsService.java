package com.example.group.service.impl;

import com.example.group.service.ShiftStatsService;
import com.example.group.service.UserShiftCount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
public class InMemoryShiftStatsService implements ShiftStatsService {

    @Override
    public List<UserShiftCount> getLeaderboard() {
        // Stub implementation; replace with real data source
        log.debug("Returning stub leaderboard data");
        return List.of(
                new UserShiftCount(1L, "Ім'я", "Прізвище", 146),
                new UserShiftCount(2L, "Ім'я", "Прізвище", 120),
                new UserShiftCount(3L, "Ім'я", "Прізвище", 117)
        ).stream().sorted(Comparator.comparingInt(UserShiftCount::count).reversed()).toList();
    }
}
