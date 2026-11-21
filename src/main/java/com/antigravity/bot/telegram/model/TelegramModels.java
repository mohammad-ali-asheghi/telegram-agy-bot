package com.antigravity.bot.telegram.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class TelegramModels {

    public static class GetUpdatesResponse {
        public boolean ok;
        public List<Update> result;
        public String description;
    }

    public static class SendMessageResponse {
        public boolean ok;
        public Message result;
        public String description;
    }

    public static class Update {
        @SerializedName("update_id")
        public long updateId;
        public Message message;
        @SerializedName("callback_query")
        public CallbackQuery callbackQuery;
    }

    public static class Message {
        @SerializedName("message_id")
        public int messageId;
        public User from;
        public Chat chat;
        public String text;
        public long date;
    }

    public static class CallbackQuery {
        public String id;
        public User from;
        public Message message;
        public String data;
    }

    public static class User {
        public long id;
        @SerializedName("is_bot")
        public boolean isBot;
        @SerializedName("first_name")
        public String firstName;
        @SerializedName("last_name")
        public String lastName;
        public String username;
    }

    public static class Chat {
        public long id;
        public String type;
        public String title;
        public String username;
    }

    public static class InlineKeyboardMarkup {
        @SerializedName("inline_keyboard")
        public List<List<InlineKeyboardButton>> inlineKeyboard;

        public InlineKeyboardMarkup() {}

        public InlineKeyboardMarkup(List<List<InlineKeyboardButton>> inlineKeyboard) {
            this.inlineKeyboard = inlineKeyboard;
        }
    }

    public static class InlineKeyboardButton {
        public String text;
        @SerializedName("callback_data")
        public String callbackData;

        public InlineKeyboardButton() {}

        public InlineKeyboardButton(String text, String callbackData) {
            this.text = text;
            this.callbackData = callbackData;
        }
    }
}
