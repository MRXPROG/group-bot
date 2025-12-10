package com.example.group.service;

import com.example.group.dto.ParsedShiftRequest;
import com.example.group.dto.SlotDTO;

public interface SlotService {
    SlotMatchResult findMatchingSlot(ParsedShiftRequest req);
}
