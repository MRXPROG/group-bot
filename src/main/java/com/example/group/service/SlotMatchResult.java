package com.example.group.service;

import com.example.group.dto.SlotDTO;

public record SlotMatchResult(SlotDTO slot, boolean ambiguous) {
    public boolean found() {
        return slot != null;
    }
}

