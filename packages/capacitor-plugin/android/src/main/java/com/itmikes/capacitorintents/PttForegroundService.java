package com.itmikes.capacitorintents;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import java.util.Iterator;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class PttForegroundService extends Service {
    private static final String TAG = "PttService";  // Log tag for easy filtering
    private static final String CHANNEL_ID = "ptt_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String CUSTOM_ACTION_DOWN = "com.itmikes.ptt.event.down";
    private static final String CUSTOM_ACTION_UP = "com.itmikes.ptt.event.up";

    private BroadcastReceiver pttReceiver;
    private boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created - Setting up notification channel and receiver");
        createNotificationChannel();
        pttReceiver = createPttReceiver();
        registerPttReceiver();
        Log.d(TAG, "Receiver registered for PTT intents");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called - Starting foreground mode");
        if (!isRunning) {
            try {
                startForeground(NOTIFICATION_ID, createNotification());
                Log.d(TAG, "Foreground started successfully - Notification should be visible");
                isRunning = true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground: " + e.getMessage(), e);
            }
        }
        return START_STICKY;  // Restart if killed by system
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;  // Not bound service
    }

    @Override
   public void onDestroy() {
        Log.d(TAG, "Service destroying - Unregistering receiver");
        if (pttReceiver != null) {
            unregisterReceiver(pttReceiver);
        }
        stopForeground(true);
        isRunning = false;
        super.onDestroy();
    }

    private void registerPttReceiver() {
        IntentFilter filter = new IntentFilter();
        // Hardcoded for Ulefone (add others as needed; could pass via intent extras for generality)
        filter.addAction("com.ulefone.ptt.key.down");
        filter.addAction("com.ulefone.ptt.key.up");
        filter.addAction("android.intent.action.PTT.down");
        filter.addAction("android.intent.action.PTT.up");
        filter.addAction("com.sonim.intent.action.PTT_KEY_DOWN");
        filter.addAction("com.sonim.intent.action.PTT_KEY_UP");
        filter.addAction("com.runbo.ptt.key.down");
        filter.addAction("com.runbo.ptt.key.up");
        filter.addAction("com.ptt.key.down");
        filter.addAction("com.ptt.key.up");
        filter.addAction("com.zello.ptt.down");
        filter.addAction("com.zello.ptt.up");
        try {
            registerReceiver(pttReceiver, filter, Context.RECEIVER_EXPORTED);
            
            // Build string of actions for logging
            String actionList = "none";
            Iterator<String> actionsIt = filter.actionsIterator();
            if (actionsIt != null) {
                StringBuilder sb = new StringBuilder();
                while (actionsIt.hasNext()) {
                    sb.append(actionsIt.next()).append(", ");
                }
                if (sb.length() > 0) {
                    sb.setLength(sb.length() - 2);  // Remove trailing ", "
                    actionList = sb.toString();
                }
            }
            
            Log.d(TAG, "PTT receiver registered with filters: " + actionList);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PTT receiver: " + e.getMessage(), e);
        }
    }

    private BroadcastReceiver createPttReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "PTT intent received: " + action + " - Forwarding as custom event");
                String customAction = action.contains("down") ? CUSTOM_ACTION_DOWN : CUSTOM_ACTION_UP;
                Intent localIntent = new Intent(customAction);
                // Forward original intent data for completeness
                localIntent.putExtra("originalAction", action);
                localIntent.putExtra("timestamp", System.currentTimeMillis());
                // Add any extras from original intent
                if (intent.getExtras() != null) {
                    localIntent.putExtras(intent.getExtras());
                }
                sendBroadcast(localIntent);  // Local broadcast to plugin receiver
            }
        };
    }

    private Notification createNotification() {
        // Launch app on tap (get package launch intent)
        Intent notificationIntent = this.getPackageManager().getLaunchIntentForPackage(this.getPackageName());
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PTT Listening Active")
            .setContentText("Ready for PTT button presses")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)  // Use system icon; replace with custom if added to res
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        Log.d(TAG, "Notification created successfully");
        return notification;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "PTT Service", NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Handles PTT button in background");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Notification channel created");
            } else {
                Log.e(TAG, "Failed to get NotificationManager");
            }
        }
    }
}