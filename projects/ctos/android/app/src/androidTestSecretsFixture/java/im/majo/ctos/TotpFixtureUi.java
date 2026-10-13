package im.majo.ctos;

import static org.junit.Assert.*;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact active-window controls only. Input handshakes follow the shared release UI fixture. */
final class TotpFixtureUi implements AutoCloseable {
    static final String TARGET = "im.majo.ctos.secretsfixture";
    private final UiAutomation automation;
    private final Map<String, AccessibilityNodeInfo> editors = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private String scope = TARGET;
    interface Match { boolean accepts(AccessibilityNodeInfo node); }

    TotpFixtureUi(UiAutomation automation) { this.automation = automation; }
    void scope(String packageName) { scope = packageName; }
    String scope() { return scope; }

    AccessibilityNodeInfo activeRoot() {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null && scope.contentEquals(String.valueOf(root.getPackageName()))) return root;
        if (root != null) root.recycle();
        return null;
    }
    int activeWindow() {
        AccessibilityNodeInfo root = activeRoot();
        if (root == null) return -1;
        try { return root.getWindowId(); } finally { root.recycle(); }
    }
    AccessibilityNodeInfo find(Match match) {
        return find(match, false);
    }
    AccessibilityNodeInfo unique(Match match) {
        return find(match, true);
    }
    int count(Match match) {
        AccessibilityNodeInfo root = activeRoot(); if (root == null) return -1;
        int window = root.getWindowId(), matches = 0;
        List<AccessibilityNodeInfo> queue = new ArrayList<>(); queue.add(root);
        boolean truncated = false;
        try {
            for (int index = 0; index < queue.size() && index < 1500; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                if (node.getWindowId() == window && scope.contentEquals(String.valueOf(node.getPackageName()))
                        && node.isVisibleToUser() && match.accepts(node)) matches++;
                for (int child = 0; child < node.getChildCount(); child++) {
                    if (queue.size() >= 1500) { truncated = true; break; }
                    AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next);
                }
            }
            return truncated ? -1 : matches;
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    private AccessibilityNodeInfo find(Match match, boolean unique) {
        AccessibilityNodeInfo root = activeRoot();
        if (root == null) return null;
        int window = root.getWindowId();
        List<AccessibilityNodeInfo> queue = new ArrayList<>(); queue.add(root);
        AccessibilityNodeInfo result = null;
        try {
            for (int index = 0; index < queue.size() && index < 1500; index++) {
                AccessibilityNodeInfo node = queue.get(index);
                if (node.getWindowId() == window && scope.contentEquals(String.valueOf(node.getPackageName()))
                        && node.isVisibleToUser() && match.accepts(node)) {
                    if (!unique) return AccessibilityNodeInfo.obtain(node);
                    if (result != null) { result.recycle(); return null; }
                    result = AccessibilityNodeInfo.obtain(node);
                }
                for (int child = 0; child < node.getChildCount(); child++) {
                    AccessibilityNodeInfo next = node.getChild(child); if (next != null) queue.add(next);
                }
            }
            if (queue.size() > 1500) { if (result != null) result.recycle(); return null; }
            return result;
        } finally { for (AccessibilityNodeInfo node : queue) node.recycle(); }
    }
    static boolean exact(AccessibilityNodeInfo node, String expected) {
        for (CharSequence value : new CharSequence[]{node.getText(), node.getContentDescription(), node.getHintText(), node.getTooltipText()}) {
            if (value == null) continue;
            for (String component : value.toString().split("\\n|, ")) if (component.trim().equals(expected)) return true;
        }
        return false;
    }
    static boolean field(AccessibilityNodeInfo node, String label) {
        for (CharSequence value : new CharSequence[]{node.getHintText(), node.getContentDescription()}) {
            if (value == null) continue;
            String text = value.toString().trim();
            if (text.equals(label) || text.startsWith(label + "\n") || text.startsWith(label + ", ")) return true;
        }
        return false;
    }
    boolean has(String label) {
        AccessibilityNodeInfo node = find(n -> exact(n, label));
        if (node == null) return false;
        node.recycle(); return true;
    }
    void waitText(String label, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
            if (has(label)) return;
            sweep(attempt); SystemClock.sleep(100);
        }
        fail("Expected fixture UI phase unavailable: " + label);
    }
    void click(String label) { click(label, false); }
    void submit(String label) { click(label, true); }
    void exactSuccessAck(int originalWindow, String titleText, String bodyText) {
        assertEquals("Acknowledgement must belong to the fixture", TARGET, scope);
        assertTrue("Acknowledgement must retain its originally observed active window", originalWindow >= 0 && activeWindow() == originalWindow);
        AccessibilityNodeInfo title = null, body = null, button = null;
        try {
            title = unique(n -> passiveExact(n, titleText));
            body = unique(n -> passiveExact(n, bodyText));
            button = unique(n -> ackButton(n));
            assertNotNull("Exact success acknowledgement title must remain unique", title);
            assertNotNull("Exact success acknowledgement body must remain unique", body);
            assertNotNull("Exact success acknowledgement button must remain unique", button);
            assertTrue("Success acknowledgement changed before its actual click", title.refresh() && body.refresh() && button.refresh()
                    && ackWindowNode(title, originalWindow) && passiveExact(title, titleText)
                    && ackWindowNode(body, originalWindow) && passiveExact(body, bodyText)
                    && ackWindowNode(button, originalWindow) && ackButton(button)
                    && activeWindow() == originalWindow);
            assertTrue("Exact success acknowledgement rejected its advertised click", button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally {
            if (title != null) title.recycle(); if (body != null) body.recycle(); if (button != null) button.recycle();
        }
    }
    private static boolean passiveExact(AccessibilityNodeInfo node, String label) {
        return !node.isEditable() && !node.isClickable() && exact(node, label)
                && !node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }
    private static boolean ackButton(AccessibilityNodeInfo node) {
        return !node.isEditable() && node.isEnabled() && exact(node, "知道了")
                && "android.widget.Button".contentEquals(String.valueOf(node.getClassName()))
                && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }
    private static boolean ackWindowNode(AccessibilityNodeInfo node, int window) {
        return node.getWindowId() == window && TARGET.contentEquals(String.valueOf(node.getPackageName())) && node.isVisibleToUser();
    }
    void sweepAuthenticatorList(int attempt) {
        assertEquals("List scrolling must belong to the fixture", TARGET, scope);
        if (attempt % 4 != 0 || has("核验操作结果") || has("确认账号") || has("重命名验证码") || has("删除验证码？")) return;
        AccessibilityNodeInfo heading = unique(n -> passiveExact(n, "Authenticator"));
        AccessibilityNodeInfo adding = find(n -> passiveExact(n, "添加验证码"));
        try {
            if (heading == null || adding != null || !heading.refresh() || !ackWindowNode(heading, activeWindow())
                    || !passiveExact(heading, "Authenticator")) return;
            if (attempt % 36 == 0) {
                for (int count = 0; count < 12 && scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD, "Authenticator"); count++) SystemClock.sleep(60);
            } else scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD, "Authenticator");
        } finally { if (heading != null) heading.recycle(); if (adding != null) adding.recycle(); }
    }
    void toolbarBack(String label, String page) {
        assertEquals(TARGET, scope);
        AccessibilityNodeInfo title = unique(n -> !n.isEditable() && exact(n, page)
                && !n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
        assertNotNull("Exact toolbar title must identify the navigation band", title);
        Rect heading = new Rect(); title.getBoundsInScreen(heading); title.recycle();
        AccessibilityNodeInfo button = unique(n -> {
            Rect bounds = new Rect(); n.getBoundsInScreen(bounds);
            return n.isEnabled() && role(n, label, true) && exact(n, label)
                    && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    && bounds.right <= heading.left && bounds.top < heading.bottom && bounds.bottom > heading.top;
        });
        assertNotNull("Exact toolbar back control unavailable", button);
        try {
            assertTrue("Toolbar back left its active scope", button.refresh() && button.getWindowId() == activeWindow()
                    && button.isVisibleToUser() && button.isEnabled() && exact(button, label)
                    && button.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            assertTrue("Toolbar back rejected its advertised action", button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally { button.recycle(); }
    }
    private void click(String label, boolean buttonOnly) {
        assertEquals("Business control must belong to the fixture", TARGET, scope);
        long deadline = SystemClock.elapsedRealtime() + 12000;
        for (int attempt = 0; SystemClock.elapsedRealtime() < deadline; attempt++) {
            AccessibilityNodeInfo node = unique(n -> n.isEnabled() && !n.isEditable() && exact(n, label)
                    && role(n, label, buttonOnly) && n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            if (node != null) {
                try {
                    assertTrue("Exact control changed before click", node.refresh() && node.getWindowId() == activeWindow()
                            && scope.contentEquals(String.valueOf(node.getPackageName())) && node.isVisibleToUser() && node.isEnabled()
                            && exact(node, label) && role(node, label, buttonOnly)
                            && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
                    assertTrue("Exact control rejected advertised click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                    return;
                } finally { node.recycle(); }
            }
            sweep(attempt); SystemClock.sleep(100);
        }
        fail("Exact fixture control's own advertised action unavailable: " + label);
    }
    private static boolean role(AccessibilityNodeInfo node, String label, boolean buttonOnly) {
        String role = String.valueOf(node.getClassName());
        if (role.equals("android.widget.Button")) return true;
        if (buttonOnly) return false;
        if (Arrays.asList("设置", "工作台", "安全", "Authenticator").contains(label))
            return role.equals("android.view.View") || role.equals("android.widget.RadioButton");
        return role.equals("android.widget.RadioButton") && node.isCheckable();
    }
    void beginForm() {
        for (AccessibilityNodeInfo node : editors.values()) node.recycle();
        editors.clear(); values.clear();
    }
    void setText(String label, String value) {
        assertEquals(TARGET, scope);
        long deadline = SystemClock.elapsedRealtime() + 12000;
        AccessibilityNodeInfo node = null;
        for (int attempt = 0; SystemClock.elapsedRealtime() < deadline && node == null; attempt++) {
            node = unique(n -> n.isEditable() && n.isEnabled() && field(n, label));
            if (node == null) { sweep(attempt); SystemClock.sleep(100); }
        }
        assertNotNull("Exact editable label could not be bound", node);
        AccessibilityNodeInfo previous = editors.put(label, node);
        if (previous != null) previous.recycle();
        deadline = SystemClock.elapsedRealtime() + 5000;
        boolean focused = false;
        do {
            if (readyEditor(node, label) && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { focused = true; break; }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Exact editor did not accept its focus click", focused);
        deadline = SystemClock.elapsedRealtime() + 5000;
        boolean inputReady = false;
        do {
            if (readyEditor(node, label) && node.isFocused()
                    && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) { inputReady = true; break; }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Advertised SET_TEXT plus actual input focus is required", inputReady);
        for (Map.Entry<String, String> entered : values.entrySet()) {
            if (entered.getKey().equals(label)) continue;
            AccessibilityNodeInfo earlier = editors.get(entered.getKey());
            assertTrue("Earlier exact field expired after focus rebuild", earlier.refresh() && field(earlier, entered.getKey())
                    && valueMatches(earlier, entered.getValue()));
        }
        Bundle arguments = new Bundle(); arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        deadline = SystemClock.elapsedRealtime() + 5000;
        boolean dispatched = false;
        do {
            if (readyEditor(node, label) && node.isFocused()
                    && node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
                assertTrue("Advertised set-text was rejected", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
                dispatched = true; break;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("No unadvertised input fallback is permitted", dispatched);
        values.put(label, value);
        deadline = SystemClock.elapsedRealtime() + 5000; int samples = 0;
        do {
            if (node.refresh() && valueMatches(node, value)) {
                if (++samples == 3) return;
            } else samples = 0;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Bound editor did not retain the expected input");
    }
    private boolean readyEditor(AccessibilityNodeInfo node, String label) {
        return node.refresh() && node.getWindowId() == activeWindow() && scope.contentEquals(String.valueOf(node.getPackageName()))
                && node.isVisibleToUser() && node.isEnabled() && node.isEditable() && field(node, label);
    }
    private static boolean valueMatches(AccessibilityNodeInfo node, String expected) {
        CharSequence actual = node.getText();
        // Masked editors publish display length, not necessarily their raw value.
        // Their authenticated vault/TOTP outcome is checked by the owning test.
        return actual != null && (node.isPassword() ? actual.length() == expected.length() : expected.contentEquals(actual));
    }
    private void sweep(int attempt) {
        if (attempt % 4 != 0) return;
        if (attempt % 36 == 0) {
            for (int count = 0; count < 12 && scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD); count++) SystemClock.sleep(60);
        } else scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }
    private boolean scroll(AccessibilityNodeInfo.AccessibilityAction action) {
        return scroll(action, null);
    }
    private boolean scroll(AccessibilityNodeInfo.AccessibilityAction action, String exactPage) {
        AccessibilityNodeInfo root = activeRoot(); if (root == null) return false;
        int window = root.getWindowId(); Rect bounds = new Rect(); root.getBoundsInScreen(bounds); root.recycle();
        AccessibilityNodeInfo scroll = unique(n -> {
            if (n.isEditable() || !n.isEnabled() || !n.isScrollable() || !n.getActionList().contains(action)) return false;
            Rect own = new Rect(); n.getBoundsInScreen(own);
            if (own.width() < 64 || own.height() < 64 || !bounds.contains(own)) return false;
            if ("android.widget.ScrollView".contentEquals(String.valueOf(n.getClassName()))) return true;
            AccessibilityNodeInfo.CollectionInfo collection = n.getCollectionInfo();
            return TARGET.equals(scope) && "android.view.View".contentEquals(String.valueOf(n.getClassName()))
                    && collection != null && collection.getRowCount() > 0 && collection.getRowCount() <= 4096
                    && collection.getColumnCount() == 0 && !collection.isHierarchical()
                    && collection.getSelectionMode() == AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE
                    && toolbarAbove(n, bounds);
        });
        if (scroll == null) return false;
        try {
            assertTrue("Page scroll must remain in the same active window", scroll.refresh() && scroll.getWindowId() == window
                    && activeWindow() == window && scope.contentEquals(String.valueOf(scroll.getPackageName()))
                    && scroll.isVisibleToUser() && scroll.isEnabled() && scroll.isScrollable() && !scroll.isEditable()
                    && scroll.getActionList().contains(action) && (exactPage == null || exactListWindow(window, exactPage)));
            return scroll.performAction(action.getId());
        } finally { scroll.recycle(); }
    }
    private boolean exactListWindow(int window, String page) {
        if (!TARGET.equals(scope) || activeWindow() != window || has("核验操作结果") || has("确认账号")
                || has("重命名验证码") || has("删除验证码？")) return false;
        AccessibilityNodeInfo heading = unique(n -> passiveExact(n, page));
        AccessibilityNodeInfo adding = find(n -> passiveExact(n, "添加验证码"));
        try { return heading != null && adding == null && heading.refresh() && ackWindowNode(heading, window)
                && passiveExact(heading, page) && activeWindow() == window; }
        finally { if (heading != null) heading.recycle(); if (adding != null) adding.recycle(); }
    }
    private boolean toolbarAbove(AccessibilityNodeInfo scroll, Rect window) {
        Rect viewport = new Rect(); scroll.getBoundsInScreen(viewport);
        AccessibilityNodeInfo title = find(n -> {
            if (n.isEditable() || n.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) return false;
            Rect own = new Rect(); n.getBoundsInScreen(own);
            if (own.isEmpty() || !window.contains(own) || own.bottom > viewport.top) return false;
            for (String label : new String[]{"ctOS", "工作台", "Authenticator", "添加验证码", "安全", "创建秘密库", "解锁秘密库",
                    "验证码操作", "重命名验证码", "删除验证码？", "设置秘密库", "开启本设备快捷解锁？"}) if (exact(n, label)) return true;
            return false;
        });
        if (title == null) return false;
        title.recycle(); return true;
    }
    @Override public void close() { beginForm(); }
}
