package com.datasync.core.job;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 同步事件总线 — 用于向 WebSocket 推送实时消息 */
public class SyncEventBus {
    private static final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public static void subscribe(Consumer<String> listener) { listeners.add(listener); }
    public static void unsubscribe(Consumer<String> listener) { listeners.remove(listener); }
    public static void publish(String message) { listeners.forEach(l -> l.accept(message)); }
}
