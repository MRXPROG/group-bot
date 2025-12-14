package com.example.group.service.impl;

import com.example.group.controllers.MainBotApiClient;
import com.example.group.dto.SlotDTO;
import com.example.group.model.UserFlowState;
import com.example.group.model.GroupShiftMessage;
import com.example.group.repository.GroupShiftMessageRepository;
import com.example.group.repository.UserFlowStateRepository;
import com.example.group.service.BookingFlowService;
import com.example.group.service.SlotPostUpdater;
import com.example.group.service.util.MessageCleaner;
import com.example.group.service.exception.BookingConflictException;
import com.example.group.service.exception.BookingTimeRestrictionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingFlowServiceImpl implements BookingFlowService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final UserFlowStateRepository stateRepo;
    private final MainBotApiClient mainApi;
    private final GroupShiftMessageRepository shiftMsgRepo;
    private final SlotPostUpdater slotPostUpdater;
    private final MessageCleaner cleaner;

    @Override
    public void startFlowInGroup(TelegramLongPollingBot bot, Message msg, SlotDTO slot, String userFullName) {
        Long chatId = msg.getChatId();
        Long userId = msg.getFrom().getId();
        NameParts names = resolveNames(msg, userFullName);
        String confirmedName = (names.firstName() + " " + names.lastName()).trim();

        stateRepo.findByUserId(userId)
                .ifPresent(state -> expireFlow(bot, state, null));

        String innLine = slot.isInnRequired()
                ? " • ІПН обов'язковий"
                : "";

        String text = ("""
                Записати тебе на зміну?
                📍 %s
                📅 %s • %s - %s%s
                👤 Ім'я в заявці: %s
                """
        ).formatted(
                slot.getPlaceName(),
                slot.getStart().toLocalDate().format(DATE),
                slot.getStart().toLocalTime().format(TIME),
                slot.getEnd().toLocalTime().format(TIME),
                innLine,
                confirmedName
        );

        SendMessage sm = new SendMessage(chatId.toString(), text);
        sm.setReplyToMessageId(resolveReplyMessageId(chatId, slot.getId(), msg.getMessageId()));
        sm.setReplyMarkup(buildKeyboard(slot.getId(), userId));

        try {
            Message botMsg = sendWithReplyFallback(bot, sm, chatId, slot.getId());

            UserFlowState state = UserFlowState.builder()
                    .userId(userId)
                    .chatId(chatId)
                    .firstName(names.firstName())
                    .lastName(names.lastName())
                    .userMessageId(msg.getMessageId())
                    .botMessageId(botMsg.getMessageId())
                    .slotId(slot.getId())
                    .expiresAt(LocalDateTime.now().plusSeconds(30))
                    .build();

            stateRepo.save(state);
        } catch (Exception e) {
            log.error("Failed to send confirmation message", e);
        }
    }

    @Override
    public void handleDecision(TelegramLongPollingBot bot, CallbackQuery cbq, Long slotId, String decision) {
        Long userId = cbq.getFrom().getId();

        UserFlowState state = stateRepo.findByUserId(userId).orElse(null);
        if (state == null || !state.getSlotId().equals(slotId)) {
            answer(bot, cbq, "⏳ Час вийшов. Створи нову заявку.");
            return;
        }

        if ("NO".equalsIgnoreCase(decision)) {
            expireFlow(bot, state, cbq);
            return;
        }

        if ("YES".equalsIgnoreCase(decision)) {
            try {
                String firstName = state.getFirstName();
                String lastName = state.getLastName();

                // fallback in case user changed name during the flow or we didn't capture it
                if (firstName == null && cbq.getFrom() != null) {
                    firstName = cbq.getFrom().getFirstName();
                }
                if (lastName == null && cbq.getFrom() != null) {
                    lastName = cbq.getFrom().getLastName();
                }

                mainApi.createBooking(userId, slotId, firstName, lastName);

                SendMessage done = new SendMessage(
                        state.getChatId().toString(),
                        "✅ Заявку прийнято. Статус дивись у головному боті"
                );
                done.setReplyToMessageId(resolveReplyMessageId(state));

                Message m = sendWithReplyFallback(bot, done, state.getChatId(), slotId);
                cleaner.deleteLater(bot, state.getChatId(), m.getMessageId(), 15);

                slotPostUpdater.refreshSlotPosts();
            } catch (BookingConflictException e) {
                log.warn("User {} already has booking for slot {}", userId, slotId);
                answer(bot, cbq, "ℹ️ Ти вже у цій зміні.");
            } catch (BookingTimeRestrictionException e) {
                log.warn("Booking time restriction for user {} and slot {}: {}", userId, slotId, e.getMessage());
                answer(bot, cbq, e.getMessage());
            } catch (Exception e) {
                log.error("Failed to create booking: {}", e.getMessage());
                answer(bot, cbq, "❌ Не вийшло створити заявку. Спробуй пізніше.");
            }

            expireFlow(bot, state, cbq);
        }
    }

    @Override
    public void expireFlow(TelegramLongPollingBot bot, UserFlowState state, CallbackQuery cbqOrNull) {
        Long chatId = state.getChatId();

        if (cbqOrNull == null) {
            try {
                SendMessage timeoutMsg = new SendMessage(
                        chatId.toString(),
                        "⏰ Час вийшов. Створи заявку ще раз."
                );
                timeoutMsg.setReplyToMessageId(resolveReplyMessageId(state));
                Message m = sendWithReplyFallback(bot, timeoutMsg, chatId, state.getSlotId());
                cleaner.deleteLater(bot, chatId, m.getMessageId(), 15);
            } catch (Exception e) {
                log.warn("Failed to send timeout message: {}", e.getMessage());
            }
        }

        cleaner.deleteNow(bot, chatId, state.getUserMessageId());
        cleaner.deleteNow(bot, chatId, state.getBotMessageId());

        stateRepo.delete(state);

        if (cbqOrNull != null) {
            answer(bot, cbqOrNull, "✅ Готово");
        }
    }

    private Integer resolveReplyMessageId(UserFlowState state) {
        return resolveReplyMessageId(state.getChatId(), state.getSlotId(), state.getUserMessageId());
    }

    private Integer resolveReplyMessageId(Long chatId, Long slotId, Integer fallback) {
        return shiftMsgRepo.findByChatIdAndSlotId(chatId, slotId)
                .map(GroupShiftMessage::getMessageId)
                .orElse(fallback);
    }

    private NameParts resolveNames(Message msg, String userFullName) {
        String firstName = trimToNull(msg.getFrom().getFirstName());
        String lastName = trimToNull(msg.getFrom().getLastName());

        NameParts parsed = splitFullName(userFullName);

        if (parsed.firstName() != null && parsed.lastName() != null) {
            firstName = parsed.firstName();
            lastName = parsed.lastName();
        } else {
            if (firstName == null) firstName = parsed.firstName();
            if (lastName == null) lastName = parsed.lastName();
        }

        if (firstName == null) firstName = "Невідомо";
        if (lastName == null) lastName = "Невідомо";

        return new NameParts(firstName, lastName);
    }

    private NameParts splitFullName(String fullName) {
        if (fullName == null) {
            return new NameParts(null, null);
        }

        String[] parts = Arrays.stream(fullName.trim().split("\\s+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (parts.length < 2 || parts.length > 3) {
            return new NameParts(null, null);
        }

        String firstName = parts[0];
        String lastName = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));

        return new NameParts(firstName, lastName);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private InlineKeyboardMarkup buildKeyboard(Long slotId, Long userId) {
        InlineKeyboardButton yes = new InlineKeyboardButton();
        yes.setText("✅ Так");
        yes.setCallbackData("CFM:" + slotId + ":" + userId + ":YES");

        InlineKeyboardButton no = new InlineKeyboardButton();
        no.setText("❌ Ні");
        no.setCallbackData("CFM:" + slotId + ":" + userId + ":NO");

        return new InlineKeyboardMarkup(List.of(List.of(yes, no)));
    }

    private void answer(TelegramLongPollingBot bot, CallbackQuery cbq, String text) {
        try {
            bot.execute(
                    AnswerCallbackQuery.builder()
                            .callbackQueryId(cbq.getId())
                            .text(text)
                            .showAlert(false)
                            .build()
            );
        } catch (Exception ignored) {
        }
    }

    private Message sendWithReplyFallback(TelegramLongPollingBot bot,
                                          SendMessage message,
                                          Long chatId,
                                          Long slotId) throws Exception {
        Integer replyTo = message.getReplyToMessageId();
        try {
            return bot.execute(message);
        } catch (TelegramApiException e) {
            if (replyTo != null && isMessageMissing(e)) {
                log.warn("BookingFlow: reply target {} is missing for slot {}, sending without reply", replyTo, slotId);
                message.setReplyToMessageId(null);
                cleanupMissingShiftMessage(chatId, slotId, replyTo);
                return bot.execute(message);
            }
            throw e;
        }
    }

    private void cleanupMissingShiftMessage(Long chatId, Long slotId, Integer replyTo) {
        shiftMsgRepo.findByChatIdAndSlotId(chatId, slotId)
                .filter(record -> Objects.equals(record.getMessageId(), replyTo))
                .ifPresent(record -> {
                    log.info("BookingFlow: removing stale shift message record {} for slot {}", replyTo, slotId);
                    shiftMsgRepo.delete(record);
                });
    }

    private boolean isMessageMissing(TelegramApiException e) {
        Integer code = e.getErrorCode();
        String apiResponse = Optional.ofNullable(e.getApiResponse()).orElse("");
        String description = Optional.ofNullable(e.getMessage()).orElse("");
        String payload = (apiResponse + " " + description).toLowerCase();
        return Objects.equals(code, 400) && (payload.contains("message to reply not found") ||
                payload.contains("message to edit not found") ||
                payload.contains("message to delete not found") ||
                payload.contains("message is not modified"));
    }

    private record NameParts(String firstName, String lastName) {}
}
