package com.passpress.bleKeyboard;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.LinkedBlockingQueue;

@SuppressLint("MissingPermission")
public class HidKeyboardService extends Service {

    private static final String TAG = "HidKeyboardService";

    private static final String CHANNEL_ID = "passpress_ble_fg";
    private static final int NOTIF_ID = 1001;

    // ─── HID Report Map: 8-byte input report (Report ID 1) ───────────────────
    private static final byte[] HID_REPORT_MAP = {
        (byte)0x05, (byte)0x01,  // Usage Page (Generic Desktop)
        (byte)0x09, (byte)0x06,  // Usage (Keyboard)
        (byte)0xA1, (byte)0x01,  // Collection (Application)
        (byte)0x85, (byte)0x01,  //   Report ID (1)
        (byte)0x05, (byte)0x07,  //   Usage Page (Key Codes)
        (byte)0x19, (byte)0xE0,  //   Usage Minimum (224) – Left Control
        (byte)0x29, (byte)0xE7,  //   Usage Maximum (231) – Right GUI
        (byte)0x15, (byte)0x00,  //   Logical Minimum (0)
        (byte)0x25, (byte)0x01,  //   Logical Maximum (1)
        (byte)0x75, (byte)0x01,  //   Report Size (1)
        (byte)0x95, (byte)0x08,  //   Report Count (8)
        (byte)0x81, (byte)0x02,  //   Input (Data, Variable, Absolute) – Modifier byte
        (byte)0x95, (byte)0x01,  //   Report Count (1)
        (byte)0x75, (byte)0x08,  //   Report Size (8)
        (byte)0x81, (byte)0x01,  //   Input (Constant) – Reserved byte
        (byte)0x95, (byte)0x06,  //   Report Count (6)
        (byte)0x75, (byte)0x08,  //   Report Size (8)
        (byte)0x15, (byte)0x00,  //   Logical Minimum (0)
        (byte)0x25, (byte)0x65,  //   Logical Maximum (101)
        (byte)0x05, (byte)0x07,  //   Usage Page (Key Codes)
        (byte)0x19, (byte)0x00,  //   Usage Minimum (0)
        (byte)0x29, (byte)0x65,  //   Usage Maximum (101)
        (byte)0x81, (byte)0x00,  //   Input (Data, Array) – Key array (6 keys)
        (byte)0xC0               // End Collection
    };

    private static final String PREFS_NAME      = "passpress_prefs";
    private static final String LAST_DEVICE_KEY = "last_connected_device";
    private static final int REPORT_ID = 1;

    // ─── Instance fields ─────────────────────────────────────────────────────
    private BluetoothAdapter      bluetoothAdapter;
    private BluetoothHidDevice    hidDevice;
    private BluetoothDevice       connectedDevice    = null;
    private ConnectionListener    connectionListener = null;
    private static HidKeyboardService instance;
    private volatile boolean      isReady            = false;
    private volatile boolean      isRegistered       = false;

    private final LinkedBlockingQueue<String> sendQueue   = new LinkedBlockingQueue<>();
    private volatile boolean                  workerAlive = false;
    private final Handler                     mainHandler = new Handler(Looper.getMainLooper());

    // ─── Listener interface ──────────────────────────────────────────────────
    public interface ConnectionListener {
        void onConnectionStatusChanged(boolean isConnected, String deviceName);
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();

        Notification notification = buildNotification("Bluetooth Keyboard starting…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        Log.d(TAG, "HidKeyboardService created – running foreground service");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if ("ACTION_DISCONNECT".equals(action)) {
                Log.d(TAG, "Disconnect action received from notification");
                disconnectDevice();
                updateNotification("Disconnected (User Action)");
                return START_STICKY;
            } else if ("ACTION_RECONNECT".equals(action)) {
                Log.d(TAG, "Reconnect action received from notification");
                String lastDevice = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                        .getString(LAST_DEVICE_KEY, null);
                if (lastDevice != null) {
                    updateNotification("Reconnecting...");
                    connectToDevice(lastDevice);
                } else {
                    updateNotification("No saved device to reconnect");
                }
                return START_STICKY;
            }
        }
        
        if (!isRegistered) {
            setupHidDevice();
        }
        startSendWorker();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        workerAlive = false;
        cleanup();
    }

    // ─── Public API ──────────────────────────────────────────────────────────
    public static HidKeyboardService getInstance() { return instance; }
    public boolean isServiceReady() { return isReady && isRegistered; }

    public boolean waitForReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!isServiceReady() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isServiceReady();
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
        if (listener != null) {
            boolean connected = (connectedDevice != null);
            String name = connected ? safeGetName(connectedDevice) : null;
            mainHandler.post(() -> listener.onConnectionStatusChanged(connected, name));
        }
    }

    public void sendKeySequence(String text) {
        if (text != null && !text.isEmpty()) {
            sendQueue.offer(text);
        }
    }

    public void connectToDevice(String address) {
        if (hidDevice == null || address == null) {
            Log.w(TAG, "connectToDevice: hidDevice not ready or address null");
            return;
        }
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.e(TAG, "connectToDevice: Could not resolve address " + address);
            return;
        }

        // If already connected to another device, disconnect first
        if (connectedDevice != null && !connectedDevice.getAddress().equals(address)) {
            Log.d(TAG, "Disconnecting from " + connectedDevice.getAddress() + " before connecting to " + address);
            hidDevice.disconnect(connectedDevice);
        }

        Log.d(TAG, "Connecting to " + address);
        boolean result = hidDevice.connect(device);
        Log.d(TAG, "connect() returned: " + result);
    }

    public void disconnectDevice() {
        if (hidDevice != null && connectedDevice != null) {
            Log.d(TAG, "Disconnecting from " + connectedDevice.getAddress());
            hidDevice.disconnect(connectedDevice);
        }
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    // ─── BluetoothHidDevice Setup ────────────────────────────────────────────
    private void setupHidDevice() {
        BluetoothManager btMgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btMgr == null) {
            Log.e(TAG, "BluetoothManager is null");
            return;
        }
        bluetoothAdapter = btMgr.getAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth adapter unavailable or disabled");
            return;
        }

        // Enable discoverability by setting adapter name
        Log.d(TAG, "Bluetooth adapter name: " + bluetoothAdapter.getName());

        boolean gotProxy = bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE);
        if (!gotProxy) {
            Log.e(TAG, "getProfileProxy(HID_DEVICE) failed – device may not support HID Device profile");
        } else {
            Log.d(TAG, "getProfileProxy(HID_DEVICE) requested successfully");
        }
    }

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            Log.d(TAG, "HID Device profile proxy connected");
            hidDevice = (BluetoothHidDevice) proxy;
            registerHidApp();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HID_DEVICE) return;
            Log.w(TAG, "HID Device profile proxy disconnected");
            hidDevice = null;
            isRegistered = false;
            isReady = false;
        }
    };

    private void registerHidApp() {
        if (hidDevice == null) {
            Log.e(TAG, "registerHidApp: hidDevice is null");
            return;
        }

        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
            "PassPress BLE",          // name
            "BLE Keyboard",           // description
            "PassPress",              // provider
            BluetoothHidDevice.SUBCLASS1_KEYBOARD, // subclass (0x40)
            HID_REPORT_MAP            // descriptors
        );

        // QoS settings for interrupt channel (keyboard reports)
        BluetoothHidDeviceAppQosSettings qosOut = new BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,   // token rate
            9,     // token bucket size
            0,     // peak bandwidth
            11250, // latency (11.25ms)
            BluetoothHidDeviceAppQosSettings.MAX
        );

        boolean registered = hidDevice.registerApp(sdp, null, qosOut, Runnable::run, hidCallback);
        Log.d(TAG, "registerApp() returned: " + registered);
    }

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
            Log.d(TAG, "onAppStatusChanged: registered=" + registered);
            isRegistered = registered;
            if (registered) {
                isReady = true;
                notifyConnectionStatusChanged(false, null);
                updateNotification("Ready – pair in Windows Bluetooth Settings");
                Log.d(TAG, "✓ HID Keyboard app registered – ready for connections");

                // Auto-connect to the last known device
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String lastAddr = prefs.getString(LAST_DEVICE_KEY, null);
                if (lastAddr != null) {
                    Log.d(TAG, "Auto-connecting to last device: " + lastAddr);
                    connectToDevice(lastAddr);
                }
            } else {
                isReady = false;
                updateNotification("HID app unregistered");
                Log.w(TAG, "HID Keyboard app unregistered");
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.d(TAG, "onConnectionStateChanged: device=" + safeGetName(device) + " state=" + state);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "✓ Device connected: " + safeGetName(device));
                connectedDevice = device;
                notifyConnectionStatusChanged(true, device);
                updateNotification("Connected to: " + safeGetName(device));

                // Save last connected device
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(LAST_DEVICE_KEY, device.getAddress()).apply();

            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected: " + safeGetName(device));
                connectedDevice = null;
                notifyConnectionStatusChanged(false, null);
                updateNotification("Disconnected – ready to reconnect");
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            Log.d(TAG, "onGetReport: type=" + type + " id=" + id + " bufferSize=" + bufferSize);
            if (hidDevice != null) {
                // Respond with empty report
                byte[] report = new byte[]{0, 0, 0, 0, 0, 0, 0, 0};
                hidDevice.replyReport(device, type, id, report);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            Log.d(TAG, "onSetReport: type=" + type + " id=" + id);
            // Acknowledge the report
        }

        @Override
        public void onInterruptData(BluetoothDevice device, byte reportId, byte[] data) {
            Log.d(TAG, "onInterruptData: reportId=" + reportId);
        }
    };

    // ─── Send Worker ─────────────────────────────────────────────────────────
    private void startSendWorker() {
        if (workerAlive) return;
        workerAlive = true;
        Thread worker = new Thread(() -> {
            Log.d(TAG, "Send worker started");
            while (workerAlive) {
                try {
                    String text = sendQueue.take();
                    sendKeySequenceInternal(text);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Send worker exception: " + e.getMessage(), e);
                }
            }
            Log.d(TAG, "Send worker stopped");
        }, "passpress-send-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void sendKeySequenceInternal(String text) {
        if (hidDevice == null || connectedDevice == null) {
            Log.e(TAG, "Not connected – skipping key sequence");
            return;
        }

        android.content.SharedPreferences prefs = getSharedPreferences("passpress_prefs", android.content.Context.MODE_PRIVATE);
        int typingDelay = prefs.getInt("typing_delay", 25);

        for (char c : text.toCharArray()) {
            byte keycode  = getHidKeycode(c);
            byte modifier = getModifier(c);

            if (keycode == 0x00) {
                Log.w(TAG, "Skipping unmapped character: '" + c + "'");
                continue;
            }

            // Key press report
            byte[] pressReport = {modifier, 0x00, keycode, 0x00, 0x00, 0x00, 0x00, 0x00};
            boolean sent = hidDevice.sendReport(connectedDevice, REPORT_ID, pressReport);
            if (!sent) {
                Log.w(TAG, "sendReport (press) failed for char '" + c + "'");
            }
            sleep(typingDelay);

            // Key release report
            byte[] releaseReport = {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            hidDevice.sendReport(connectedDevice, REPORT_ID, releaseReport);
            sleep(typingDelay);
        }

        Log.d(TAG, "Sent key sequence (" + text.length() + " chars)");
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Notification ────────────────────────────────────────────────────────
    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID,
            "PassPress Keyboard Service",
            NotificationManager.IMPORTANCE_LOW
        );
        ch.setDescription("Maintains Bluetooth HID Keyboard connection");
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String status) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("PassPress Keyboard")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
                
        if (connectedDevice != null) {
            Intent discIntent = new Intent(this, HidKeyboardService.class).setAction("ACTION_DISCONNECT");
            PendingIntent pDisc = PendingIntent.getService(this, 1, discIntent, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "Disconnect", pDisc);
        } else {
            Intent recIntent = new Intent(this, HidKeyboardService.class).setAction("ACTION_RECONNECT");
            PendingIntent pRec = PendingIntent.getService(this, 2, recIntent, PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "Reconnect", pRec);
        }
        
        return builder.build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(status));
    }

    // ─── Connection Status ───────────────────────────────────────────────────
    private void notifyConnectionStatusChanged(boolean isConnected, BluetoothDevice device) {
        if (connectionListener != null) {
            String name = (device != null) ? safeGetName(device) : null;
            mainHandler.post(() -> connectionListener.onConnectionStatusChanged(isConnected, name));
        }
    }

    private String safeGetName(BluetoothDevice device) {
        if (device == null) return "Unknown";
        try {
            String name = device.getName();
            return (name != null && !name.isEmpty()) ? name : device.getAddress();
        } catch (Exception e) {
            return device.getAddress();
        }
    }

    // ─── HID Keycode Mapping ─────────────────────────────────────────────────
    private byte getHidKeycode(char c) {
        if (c >= 'a' && c <= 'z') return (byte)(0x04 + (c - 'a'));
        if (c >= 'A' && c <= 'Z') return (byte)(0x04 + (c - 'A'));
        if (c >= '1' && c <= '9') return (byte)(0x1E + (c - '1'));
        if (c == '0')              return 0x27;

        switch (c) {
            case ' ':  return 0x2C;
            case '\n': return 0x28;
            case '\t': return 0x2B;
            case '\b': return 0x2A;
            case '!': return 0x1E;
            case '@': return 0x1F;
            case '#': return 0x20;
            case '$': return 0x21;
            case '%': return 0x22;
            case '^': return 0x23;
            case '&': return 0x24;
            case '*': return 0x25;
            case '(': return 0x26;
            case ')': return 0x27;
            case '-': case '_': return 0x2D;
            case '=': case '+': return 0x2E;
            case '[': case '{': return 0x2F;
            case ']': case '}': return 0x30;
            case '\\': case '|': return 0x31;
            case ';': case ':': return 0x33;
            case '\'': case '"': return 0x34;
            case '`': case '~': return 0x35;
            case ',': case '<': return 0x36;
            case '.': case '>': return 0x37;
            case '/': case '?': return 0x38;
            default: return 0x00;
        }
    }

    private byte getModifier(char c) {
        if (c >= 'A' && c <= 'Z') return 0x02; // Left Shift
        switch (c) {
            case '!': case '@': case '#': case '$': case '%':
            case '^': case '&': case '*': case '(': case ')':
            case '_': case '+': case '{': case '}': case '|':
            case ':': case '"': case '~': case '<': case '>':
            case '?':
                return 0x02; // Left Shift
            default:
                return 0x00;
        }
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────
    private void cleanup() {
        if (hidDevice != null) {
            if (connectedDevice != null) {
                try { hidDevice.disconnect(connectedDevice); } catch (Exception ignored) {}
            }
            try { hidDevice.unregisterApp(); } catch (Exception ignored) {}
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice);
        }
        isRegistered = false;
        isReady      = false;
        instance     = null;
    }
}
