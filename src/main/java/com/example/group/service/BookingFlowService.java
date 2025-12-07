package com.example.group.service;

import com.example.group.dto.SlotDTO;
import com.example.group.model.UserFlowState;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;


public interface BookingFlowService {

    void startFlowInGroup(TelegramLongPollingBot bot, Message msg, SlotDTO slot);

    void handleDecision(TelegramLongPollingBot bot, CallbackQuery cbq, Long slotId, String decision);

    void expireFlow(TelegramLongPollingBot bot, UserFlowState state, CallbackQuery cbqOrNull);
}
