package com.antigravity.bot.telegram.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class TelegramModels {

    public static class GetUpdatesResponse {
        public boolean ok;
        public List<Update> result;
        public String description;
    }

    public static class Update {
        @SerializedName("update_id")
        public long updateId;
        public Message message;
    }

    public static class Message {
        @SerializedName("message_id")
        public int messageId;
        public User from;
        public Chat chat;
        public String text;
        public long date;
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
}
