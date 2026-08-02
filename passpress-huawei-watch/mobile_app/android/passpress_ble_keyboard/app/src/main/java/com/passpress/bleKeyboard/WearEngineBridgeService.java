package com.passpress.bleKeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.huawei.hmf.tasks.OnFailureListener;
import com.huawei.hmf.tasks.OnSuccessListener;
import com.huawei.wearengine.HiWear;
import com.huawei.wearengine.device.Device;
import com.huawei.wearengine.p2p.Message;
import com.huawei.wearengine.p2p.P2pClient;
import com.huawei.wearengine.p2p.Receiver;
import com.huawei.wearengine.p2p.SendCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class WearEngineBridgeService {

    private static final String TAG = "WearEngineBridge";
    private static final String WATCH_PACKAGE_NAME = "com.passpress.huaweiwatch";
    private static final String PREFS_NAME = "passpress_prefs";

    private final Context context;
    private P2pClient p2pClient;
    private Device connectedWatchDevice;
    private boolean isInitialized = false;

    public WearEngineBridgeService(Context context) {
        this.context = context.getApplicationContext();
    }

    public void initWearEngine() {
        try {
            p2pClient = HiWear.getP2pClient(context);
            p2pClient.setPeerPkgName(WATCH_PACKAGE_NAME);
            
            // Check connected/bonded Huawei wearable devices using getBondedDevices()
            HiWear.getDeviceClient(context).getBondedDevices()
                .addOnSuccessListener(new OnSuccessListener<List<Device>>() {
                    @Override
                    public void onSuccess(List<Device> devices) {
                        if (devices != null && !devices.isEmpty()) {
                            connectedWatchDevice = devices.get(0);
                            Log.d(TAG, "Connected/Bonded Huawei Watch: " + connectedWatchDevice.getName());
                            registerP2pReceiver(connectedWatchDevice);
                            isInitialized = true;
                        } else {
                            Log.w(TAG, "No Huawei Wearable devices bonded/connected.");
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "Failed to get bonded Huawei devices: " + e.getMessage());
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "Wear Engine initialization error: " + e.getMessage(), e);
        }
    }

    private void registerP2pReceiver(Device device) {
        Receiver receiver = new Receiver() {
            @Override
            public void onReceiveMessage(Message message) {
                if (message == null || message.getData() == null) return;
                try {
                    String jsonStr = new String(message.getData(), StandardCharsets.UTF_8);
                    Log.d(TAG, "Received message from Huawei Watch: " + jsonStr);
                    handleWatchMessage(jsonStr, device);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing watch message: " + e.getMessage());
                }
            }
        };

        p2pClient.registerReceiver(device, receiver)
            .addOnSuccessListener(new OnSuccessListener<Void>() {
                @Override
                public void onSuccess(Void unused) {
                    Log.d(TAG, "P2P Receiver registered successfully for device: " + device.getName());
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Failed to register P2P Receiver: " + e.getMessage());
                }
            });
    }

    private void handleWatchMessage(String messageStr, Device device) {
        try {
            JSONObject json = new JSONObject(messageStr);
            String action = json.optString("action", "");

            if ("TYPE_PASSWORD".equals(action)) {
                String password = json.optString("password", "");
                int slotIndex = json.optInt("slotIndex", -1);
                
                if (password.isEmpty() && slotIndex >= 0) {
                    password = SecureStorage.getInstance(context).getPassword(slotIndex);
                }

                if (password != null && !password.isEmpty()) {
                    HidKeyboardService service = HidKeyboardService.getInstance();
                    if (service != null && service.isServiceReady()) {
                        service.sendKeySequence(password);
                        sendResponseToWatch(device, "SUCCESS", "Typed password over BLE HID");
                    } else {
                        sendResponseToWatch(device, "ERROR", "BLE HID Service not ready or disconnected");
                    }
                } else {
                    sendResponseToWatch(device, "ERROR", "Password is empty");
                }
            } else if ("GET_SLOTS".equals(action)) {
                sendSlotsToWatch(device);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing watch message: " + e.getMessage(), e);
        }
    }

    private void sendSlotsToWatch(Device device) {
        try {
            JSONArray slotsArray = new JSONArray();
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            
            for (int i = 0; i < 5; i++) {
                String label = prefs.getString("slot_label_" + i, "Slot " + (i + 1));
                JSONObject slotObj = new JSONObject();
                slotObj.put("index", i);
                slotObj.put("label", label);
                slotsArray.put(slotObj);
            }

            JSONObject response = new JSONObject();
            response.put("action", "SLOTS_LIST");
            response.put("slots", slotsArray);

            sendMessageToWatch(device, response.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending slots to watch: " + e.getMessage());
        }
    }

    private void sendResponseToWatch(Device device, String status, String detail) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("action", "RESPONSE");
            resp.put("status", status);
            resp.put("detail", detail);
            sendMessageToWatch(device, resp.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error formatting response to watch: " + e.getMessage());
        }
    }

    private void sendMessageToWatch(Device device, String payload) {
        if (p2pClient == null || device == null) return;

        Message.Builder builder = new Message.Builder();
        builder.setPayload(payload.getBytes(StandardCharsets.UTF_8));
        Message message = builder.build();

        p2pClient.send(device, message, new SendCallback() {
            @Override
            public void onSendResult(int resultCode) {
                Log.d(TAG, "Message send result code: " + resultCode);
            }

            @Override
            public void onSendProgress(long progress) {
                Log.d(TAG, "Sending message progress: " + progress);
            }
        });
    }

    public boolean isInitialized() {
        return isInitialized;
    }
}
