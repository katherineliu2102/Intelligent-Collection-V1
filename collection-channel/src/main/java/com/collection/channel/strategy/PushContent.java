package com.collection.channel.strategy;

/**
 * Push 渲染结果：title + body（均已注入变量）。
 */
public class PushContent {

    private final String title;
    private final String body;

    public PushContent(String title, String body) {
        this.title = title;
        this.body = body;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }
}
