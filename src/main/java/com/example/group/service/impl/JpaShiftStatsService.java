package com.example.group.service.impl;

import com.example.group.repository.UserRepository;
import com.example.group.service.ShiftStatsService;
import com.example.group.service.UserShiftCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class JpaShiftStatsService implements ShiftStatsService {

    private final UserRepository userRepository;

    @Override
    public List<UserShiftCount> getLeaderboard() {
        log.debug("Loading leaderboard from persistent users");
        return userRepository.findTop10ByScoreNotNullOrderByScoreDesc()
                .stream()
                .map(user -> new UserShiftCount(
                        user.getId(),
                        user.getFirstName(),
                        user.getLastName(),
                        Optional.ofNullable(user.getScore()).orElse(0)
                ))
                .toList();
    }
}
