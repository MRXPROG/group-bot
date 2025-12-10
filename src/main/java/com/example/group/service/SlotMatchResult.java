package com.example.group.service;

import com.example.group.dto.SlotDTO;

import java.util.Collections;
import java.util.List;

public record SlotMatchResult(List<SlotDTO> slots) {

    public SlotMatchResult {
        slots = slots == null ? Collections.emptyList() : List.copyOf(slots);
    }

    public boolean found() {
        return slots != null && !slots.isEmpty();
    }

    public SlotDTO best() {
        return found() ? slots.get(0) : null;
    }
}

