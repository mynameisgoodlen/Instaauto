package com.example.instagramautoreactor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class InstagramReactionService extends AccessibilityService {
    private static final String INSTAGRAM = "com.instagram.android";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private long lastArmedAt = -1;
    private long lastActionAt = 0;
    private boolean reacting = false;
    private String conversationKey = "";

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || !INSTAGRAM.contentEquals(event.getPackageName())) return;
        if (!getPreferences().getBoolean(MainActivity.KEY_ACTIVE, false)) {
            primeVisibleMessages();
            return;
        }
        long armedAt = getPreferences().getLong("armed_at", 0L);
        if (armedAt != lastArmedAt || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastArmedAt = armedAt;
            primeVisibleMessages();
            conversationKey = readConversationKey();
            setStatus("Armed. Waiting for a new sent message.");
            return;
        }
        String newConversation = readConversationKey();
        if (!conversationKey.isEmpty() && !newConversation.isEmpty() && !conversationKey.equals(newConversation)) {
            conversationKey = newConversation;
            primeVisibleMessages();
            setStatus("Conversation changed. Waiting for the next new message.");
            return;
        }
        conversationKey = newConversation;
        if (reacting || System.currentTimeMillis() - lastActionAt < 1200) return;
        List<Message> messages = collectVisibleMessages();
        for (Message message : messages) {
            if (seen.add(message.key) && message.outgoing && message.nearBottom) {
                lastActionAt = System.currentTimeMillis();
                reactTo(message);
                break;
            }
        }
    }

    private android.content.SharedPreferences getPreferences() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private void primeVisibleMessages() {
        seen.clear();
        for (Message message : collectVisibleMessages()) seen.add(message.key);
    }

    private String readConversationKey() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        Rect bounds = new Rect();
        int topLimit = getResources().getDisplayMetrics().heightPixels / 5;
        String answer = "";
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        flatten(root, nodes);
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence text = node.getText();
            if (text == null || text.length() == 0) continue;
            node.getBoundsInScreen(bounds);
            if (bounds.top > 0 && bounds.top < topLimit && bounds.width() > 0 && bounds.width() < getResources().getDisplayMetrics().widthPixels * .75f) {
                String candidate = text.toString().trim();
                if (candidate.length() > 1 && !candidate.equalsIgnoreCase("Instagram") && !candidate.equalsIgnoreCase("Chats")) answer = candidate;
            }
        }
        return answer;
    }

    private List<Message> collectVisibleMessages() {
        ArrayList<Message> out = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return out;
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        flatten(root, nodes);
        Rect rect = new Rect();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence value = node.getText();
            if (value == null) continue;
            String text = value.toString().trim();
            if (text.isEmpty() || text.length() > 1500 || text.equalsIgnoreCase("Message…") || text.equalsIgnoreCase("Message")) continue;
            node.getBoundsInScreen(rect);
            if (rect.isEmpty() || rect.top < screenHeight * .18f || rect.bottom > screenHeight * .94f) continue;
            boolean outgoing = rect.centerX() > screenWidth * .57f;
            boolean nearBottom = rect.bottom > screenHeight * .55f;
            if (!outgoing || !nearBottom) continue;
            String key = text + "|" + Math.round(rect.left / 12f) + ":" + Math.round(rect.top / 12f) + ":" + Math.round(rect.right / 12f);
            out.add(new Message(text, key, rect.centerX(), rect.centerY(), true, true));
        }
        return out;
    }

    private void flatten(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) flatten(child, out);
        }
    }

    private void reactTo(Message message) {
        reacting = true;
        setStatus("New sent message found. Opening reaction controls…");
        Path path = new Path();
        path.moveTo(message.x, message.y);
        GestureDescription longPress = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 700))
                .build();
        boolean dispatched = dispatchGesture(longPress, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                handler.postDelayed(() -> clickSelectedEmoji(0, message.x, message.y), 650);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                finish(false, "Instagram cancelled the reaction gesture. No reaction was added.");
            }
        }, handler);
        if (!dispatched) finish(false, "Could not open Instagram’s reaction controls.");
    }

    private void clickSelectedEmoji(int attempt, float messageX, float messageY) {
        String emoji = getPreferences().getString(MainActivity.KEY_EMOJI, "❤️");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = root == null ? null : findEmoji(root, emoji, messageX, messageY);
        if (target != null) {
            boolean clicked = clickEmojiTarget(target);
            if (clicked) {
                int count = getPreferences().getInt(MainActivity.KEY_COUNT, 0) + 1;
                getPreferences().edit().putInt(MainActivity.KEY_COUNT, count).apply();
                finish(true, "Reacted with " + emoji + ".");
                return;
            }
        }
        if (attempt < 2) {
            handler.postDelayed(() -> clickSelectedEmoji(attempt + 1, messageX, messageY), 500);
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK);
            finish(false, "Could not identify that emoji in Instagram’s reaction picker. No reaction was added.");
        }
    }

    private AccessibilityNodeInfo findEmoji(AccessibilityNodeInfo node, String emoji, float messageX, float messageY) {
        CharSequence label = node.getText();
        CharSequence description = node.getContentDescription();
        if (matchesEmoji(label, emoji) || matchesEmoji(description, emoji)) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            float dx = Math.abs(bounds.centerX() - messageX);
            float dy = Math.abs(bounds.centerY() - messageY);
            if (bounds.width() > 0 && bounds.height() > 0 && dx < getResources().getDisplayMetrics().widthPixels * .72f && dy >= 18 && dy < dp(260)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEmoji(node.getChild(i), emoji, messageX, messageY);
            if (found != null) return found;
        }
        return null;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean clickEmojiTarget(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean matchesEmoji(CharSequence label, String emoji) {
        if (label == null) return false;
        String value = label.toString().trim();
        return value.equals(emoji) || value.startsWith(emoji + " ") || value.endsWith(" " + emoji) || value.toLowerCase().contains("react with " + emoji.toLowerCase());
    }

    private void finish(boolean success, String message) {
        reacting = false;
        setStatus(message);
    }

    private void setStatus(String value) {
        getPreferences().edit().putString(MainActivity.KEY_STATUS, value).apply();
    }

    @Override public void onInterrupt() {
        reacting = false;
    }

    private static final class Message {
        final String text, key;
        final float x, y;
        final boolean outgoing, nearBottom;
        Message(String text, String key, float x, float y, boolean outgoing, boolean nearBottom) {
            this.text = text; this.key = key; this.x = x; this.y = y; this.outgoing = outgoing; this.nearBottom = nearBottom;
        }
    }
}
