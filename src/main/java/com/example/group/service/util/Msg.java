package com.example.group.service.util;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class Msg {
    private final MessageSource messageSource;

    public String get(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }
}
