package com.tgwgroup.MiRearScreenSwitcher;

import android.os.Bundle;

import java.util.EnumMap;

/**
 * In-memory source of truth for what the rear screen should show.
 * One entry per experience type; the visible one is the highest priority.
 */
public class RearStack {
    public enum Type { MEDIA, CHARGING, NOTIFICATION } // ascending priority: NOTIFICATION > CHARGING > MEDIA

    private static final EnumMap<Type, Bundle> entries = new EnumMap<>(Type.class);

    /** Add or replace the entry for this type (newest payload wins). */
    public static synchronized void put(Type type, Bundle payload) {
        entries.put(type, payload == null ? new Bundle() : payload);
    }

    public static synchronized void remove(Type type) {
        entries.remove(type);
    }

    public static synchronized boolean contains(Type type) {
        return entries.containsKey(type);
    }

    public static synchronized Bundle get(Type type) {
        return entries.get(type);
    }

    public static synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Highest-priority active type, or null if empty. */
    public static synchronized Type top() {
        Type[] all = Type.values();
        for (int i = all.length - 1; i >= 0; i--) {
            if (entries.containsKey(all[i])) return all[i];
        }
        return null;
    }
}
