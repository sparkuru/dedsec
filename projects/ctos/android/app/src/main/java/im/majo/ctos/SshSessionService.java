package im.majo.ctos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.CompletableFuture;

/** Explicitly started owner of one SSH connection; no Intent contains connection material. */
public final class SshSessionService extends Service {
    static final String CHANNEL = "ctos_ssh";
    static final String SHOW = "im.majo.ctos.SSH_SHOW";
    static final String STOP = "im.majo.ctos.SSH_STOP";
    private static final String START = "im.majo.ctos.SSH_START";
    private static final String SESSION = "session";
    private static final int NOTIFICATION = 1603;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SshTerminalState EVENTS = new SshTerminalState();
    private static SshSessionOwner owner;
    private static SshSessionService service;
    private static ScreenOffRegistration screenOff;
    private SshSessionOwner attached;
    private boolean promoted;
    private boolean finished;
    private PowerManager.WakeLock wakeLock;

    private static final class ScreenOffRegistration {
        final Context context;
        final SshSessionOwner expected;
        final BroadcastReceiver receiver;

        ScreenOffRegistration(Context context, SshSessionOwner expected) {
            this.context = context;
            this.expected = expected;
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) stopForScreenLock(expected);
                }
            };
        }

        void register() {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
        }
    }

    private static final class SessionLifecycle implements SshSessionOwner.Lifecycle {
        SshSessionOwner expected;
        @Override public void state(String state) { updateNotification(expected, state); }
        @Override public void released() { releaseOwner(expected); }
    }

    static synchronized CompletableFuture<String> attach(Context context, SshSessionOwner.Factory factory,
            String mode, int columns, int rows) {
        if (screenLocked(context)) {
            if (owner != null) stopForScreenLock(owner);
            return failed("SSH_SCREEN_LOCKED", "Unlock the device before connecting SSH");
        }
        if (owner != null) return failed("BUSY", "SSH terminal cleanup is pending");
        Context application = context.getApplicationContext();
        SessionLifecycle lifecycle = new SessionLifecycle();
        SshSessionOwner created = new SshSessionOwner(factory, mode, columns, rows, EVENTS, MAIN::post,
                lifecycle);
        lifecycle.expected = created;
        owner = created;
        try {
            ScreenOffRegistration registration = new ScreenOffRegistration(application, created);
            registration.register();
            screenOff = registration;
            if (screenLocked(application)) {
                stopForScreenLock(created);
                return created.connected;
            }
            createChannel(application);
            Intent intent = new Intent(application, SshSessionService.class).setAction(START)
                    .putExtra(SESSION, created.id);
            application.startForegroundService(intent);
            MAIN.postDelayed(() -> {
                synchronized (SshSessionService.class) {
                    if (owner == created && (service == null || service.attached != created)) created.rejectStart();
                }
            }, 10_000);
        } catch (RuntimeException failure) { created.rejectStart(); }
        return created.connected;
    }

    static synchronized boolean active() { return owner != null; }
    static synchronized int generation() { return owner == null ? 0 : owner.generation; }

    static CompletableFuture<Void> write(int generation, byte[] bytes) {
        reconcileCurrent();
        SshSessionOwner selected;
        synchronized (SshSessionService.class) { selected = owner; }
        return selected == null ? failed("SSH_CLOSED", "SSH terminal is closed") : selected.write(generation, bytes);
    }

    static CompletableFuture<Void> resize(int generation, int columns, int rows) {
        reconcileCurrent();
        SshSessionOwner selected;
        synchronized (SshSessionService.class) { selected = owner; }
        return selected == null ? failed("SSH_CLOSED", "SSH terminal is closed") : selected.resize(generation, columns, rows);
    }

    static CompletableFuture<Void> stop(int generation) {
        SshSessionOwner selected;
        synchronized (SshSessionService.class) { selected = owner; }
        return selected == null ? failed("SSH_CLOSED", "SSH terminal is closed") : selected.stop(generation);
    }

    static void subscribe(SshTerminalState.Listener listener) {
        reconcileCurrent();
        EVENTS.subscribe(listener);
    }
    static void unsubscribe(SshTerminalState.Listener listener) { EVENTS.unsubscribe(listener); }

    static boolean focus(Intent intent) {
        reconcileCurrent();
        if (intent == null || !SHOW.equals(intent.getAction())) return false;
        String id = intent.getStringExtra(SESSION);
        synchronized (SshSessionService.class) {
            if (owner == null || owner.screenLocked() || !owner.matches(id)) return false;
            EVENTS.focus(owner.generation);
            return true;
        }
    }

    static void reconcile(Context context) {
        SshSessionOwner selected;
        synchronized (SshSessionService.class) { selected = owner; }
        if (selected != null && screenLocked(context)) stopForScreenLock(selected);
    }

    private static void reconcileCurrent() {
        ScreenOffRegistration registration;
        synchronized (SshSessionService.class) { registration = screenOff; }
        if (registration != null && screenLocked(registration.context)) stopForScreenLock(registration.expected);
    }

    private static boolean screenLocked(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power == null || keyguard == null || !power.isInteractive()
                || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked();
    }

    private static synchronized void stopForScreenLock(SshSessionOwner expected) {
        if (owner == expected) expected.stopForScreenLock(expected.id);
    }

    static void createChannel(Context context) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, "SSH 会话", NotificationManager.IMPORTANCE_LOW));
    }

    static boolean notificationsEnabled(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SshSessionOwner selected;
        synchronized (SshSessionService.class) { selected = owner; }
        String action = intent == null ? null : intent.getAction();
        String id = intent == null ? null : intent.getStringExtra(SESSION);
        if (STOP.equals(action)) {
            if (selected != null && selected.matches(id)) selected.requestStop();
            if (attached == null) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!START.equals(action) || selected == null || !selected.matches(id)) {
            if (attached == null) {
                if (START.equals(action)) promoteEmptyStart();
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (screenLocked(this) || selected.screenLocked()) {
            stopForScreenLock(selected);
            if (attached == null) { promoteEmptyStart(); stopSelf(startId); }
            return START_NOT_STICKY;
        }
        if (attached != null) return START_NOT_STICKY;
        attached = selected;
        synchronized (SshSessionService.class) { service = this; }
        try {
            createChannel(this);
            Notification notification = notification(selected, "正在连接");
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION, notification);
            promoted = true;
            acquireWakeLock();
            selected.begin();
        } catch (RuntimeException failure) { selected.rejectStart(); }
        return START_NOT_STICKY;
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private void acquireWakeLock() {
        // This lock belongs to the explicit SSH service, and is released on confirmed cleanup/destroy.
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":ssh-session");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void promoteEmptyStart() {
        createChannel(this);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(im.majo.ctos.R.drawable.ic_launcher)
                .setContentTitle("SSH 会话").setContentText("会话已结束").build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, notification);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private Notification notification(SshSessionOwner selected, String text) {
        // Flutter treats Intent data as a route; MIME distinguishes sessions without deep-linking.
        Intent show = new Intent(this, MainActivity.class).setAction(SHOW)
                .setType("application/vnd.ctos.ssh-session." + selected.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SESSION, selected.id);
        Intent stop = new Intent(this, SshSessionService.class).setAction(STOP)
                .setData(Uri.parse("ctos-ssh://session/" + selected.id + "/stop"))
                .putExtra(SESSION, selected.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openAction = PendingIntent.getActivity(this, 0, show, flags);
        PendingIntent stopAction = PendingIntent.getService(this, 0, stop, flags);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(im.majo.ctos.R.drawable.ic_launcher)
                .setContentTitle("SSH 会话")
                .setContentText(text)
                .setContentIntent(openAction)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "返回终端", openAction).build())
                .addAction(new Notification.Action.Builder(null, "停止", stopAction).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private static synchronized void updateNotification(SshSessionOwner expected, String state) {
        if (owner != expected || service == null || owner == null
                || service.attached != owner || !service.promoted) return;
        String text = "running".equals(state) ? "已连接 · 点击返回终端" : "正在关闭";
        service.getSystemService(NotificationManager.class).notify(NOTIFICATION, service.notification(owner, text));
    }

    private static synchronized void releaseOwner(SshSessionOwner expected) {
        if (owner != expected) return;
        if (screenOff != null && screenOff.expected == expected) {
            screenOff.context.unregisterReceiver(screenOff.receiver);
            screenOff = null;
        }
        SshSessionService previous = service;
        if (previous != null) {
            previous.finished = true;
            previous.releaseWakeLock();
            previous.stopForeground(STOP_FOREGROUND_REMOVE);
            previous.promoted = false;
            previous.stopSelf();
        } else owner = null;
    }

    @Override public void onDestroy() {
        SshSessionOwner previous = attached;
        synchronized (SshSessionService.class) {
            if (service == this) service = null;
            if (finished && owner == previous) owner = null;
        }
        if (previous != null && !finished) previous.requestStop();
        releaseWakeLock();
        if (promoted) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private static <T> CompletableFuture<T> failed(String code, String message) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(new VaultException(code, message));
        return result;
    }
}
