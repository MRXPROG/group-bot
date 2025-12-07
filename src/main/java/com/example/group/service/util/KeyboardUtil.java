package com.example.group.service.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

public final class KeyboardUtil {
    private KeyboardUtil() {}

    public static InlineKeyboardButton btn(String text, String data) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(data);
        return b;
    }

    public static InlineKeyboardMarkup rows(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup mk = new InlineKeyboardMarkup();
        mk.setKeyboard(rows);
        return mk;
    }
}
