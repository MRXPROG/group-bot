package com.example.group.service;

@Service
@RequiredArgsConstructor
public class FlowCleanerScheduler {

    private final UserFlowStateRepository stateRepo;
    private final BookingFlowService bookingFlow;

    @Scheduled(fixedDelay = 10_000)
    public void cleanupExpired() {
        var expired = stateRepo.findExpired(LocalDateTime.now());

        expired.forEach(state -> bookingFlow.expireFlow(state, null));
    }
}
