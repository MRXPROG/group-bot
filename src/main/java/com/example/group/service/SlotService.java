package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import com.example.group.dto.SlotDTO;

import java.util.Optional;

public interface SlotService {
    Optional<SlotDTO> findMatchingSlot(ParsedShiftRequest req);
}
