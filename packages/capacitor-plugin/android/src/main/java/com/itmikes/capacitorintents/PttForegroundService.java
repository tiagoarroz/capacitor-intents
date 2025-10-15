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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.Iterator;
import android.Manifest;
import java.io.IOException;

public class PttForegroundService extends Service {
    private static final String TAG = "PttService";  // Log tag for easy filtering
    private static final String CHANNEL_ID = "ptt_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String CUSTOM_ACTION_DOWN = "com.itmikes.ptt.event.down";
    private static final String CUSTOM_ACTION_UP = "com.itmikes.ptt.event.up";

    private BroadcastReceiver pttReceiver;
    private boolean isRunning = false;
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private AudioFocusRequest audioFocusRequest;
    private MediaRecorder mediaRecorder;
    private String currentRecordingPath;
    private FileOutputStream fos;

    private boolean isRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created - Setting up notification channel and receiver");
        createNotificationChannel();
        pttReceiver = createPttReceiver();
        registerPttReceiver();
        Log.d(TAG, "Receiver registered for PTT intents");
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PTT:WakeLock");

        // Initialize MediaRecorder for PTT
        // initializeMediaRecorder();
    }

    private void initializeMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.release();
        }
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioSamplingRate(16000);
        mediaRecorder.setAudioEncodingBitRate(128000);
        mediaRecorder.reset();  // Ensure clean state
        Log.d(TAG, "MediaRecorder reinitialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called - Starting foreground mode");
        if (!isRunning) {
            try {
                int res = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS);
                Log.d(TAG, "checkSelfPermission result: " + res);

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
                if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        try {
            if (isRecording) {
            mediaRecorder.stop();
            isRecording = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder on destroy: " + e.getMessage());
        }
        mediaRecorder.release();
        mediaRecorder = null;
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (currentRecordingPath != null) {
            new File(currentRecordingPath).delete();  // Cleanup
        }
        super.onDestroy();
    }

    private void registerPttReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.setPriority(1000);
        // Hardcoded for Ulefone (add others as needed; could pass via intent extras for generality)
        // filter.addAction("com.ulefone.ptt.key.down");
        // filter.addAction("com.ulefone.ptt.key.up");
        filter.addAction("android.intent.action.PTT.down");
        filter.addAction("android.intent.action.PTT.up");
        // filter.addAction("com.sonim.intent.action.PTT_KEY_DOWN");
        // filter.addAction("com.sonim.intent.action.PTT_KEY_UP");
        // filter.addAction("com.runbo.ptt.key.down");
        // filter.addAction("com.runbo.ptt.key.up");
        // filter.addAction("com.ptt.key.down");
        // filter.addAction("com.ptt.key.up");
        // filter.addAction("com.zello.ptt.down");
        // filter.addAction("com.zello.ptt.up");
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

                boolean isDown = action.contains("down");
                String customAction = isDown ? CUSTOM_ACTION_DOWN : CUSTOM_ACTION_UP;
                Intent localIntent = new Intent(customAction);
                localIntent.putExtra("originalAction", action);
                localIntent.putExtra("timestamp", System.currentTimeMillis());
                if (intent.getExtras() != null) {
                    localIntent.putExtras(intent.getExtras());
                }

                // Manage audio focus and wake lock (existing)
                if (isDown) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                            .setOnAudioFocusChangeListener(focusChange -> Log.d(TAG, "Audio focus changed: " + focusChange))
                            .build();
                        int result = audioManager.requestAudioFocus(audioFocusRequest);
                        Log.d(TAG, "Audio focus requested (transient): " + (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "GRANTED" : "DENIED"));
                    } else {
                        int result = audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                        Log.d(TAG, "Audio focus requested (legacy): " + (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "GRANTED" : "DENIED"));
                    }

                    if (!wakeLock.isHeld()) {
                        wakeLock.acquire(30 * 60 * 1000L);  // 30 min
                        Log.d(TAG, "Wake lock acquired for PTT");
                    }

                    // Start native recording
                    try {
                        if (mediaRecorder != null) {
                            mediaRecorder.release();
                        }
                        mediaRecorder = new MediaRecorder();
                        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                        mediaRecorder.setAudioSamplingRate(16000);
                        mediaRecorder.setAudioEncodingBitRate(128000);
                        File dir = getCacheDir();
                        if (!dir.exists()) dir.mkdirs();
                        currentRecordingPath = new File(dir, "ptt_" + System.currentTimeMillis() + ".aac").getAbsolutePath();
                        mediaRecorder.setOutputFile(currentRecordingPath);
                        mediaRecorder.prepare();
                        mediaRecorder.start();
                        isRecording = true;
                        Log.d(TAG, "Native recording started at: " + currentRecordingPath);
                        } catch (Exception e) {
                        Log.e(TAG, "Failed to start native recording: " + e.getMessage(), e);
                        isRecording = false;
                        if (mediaRecorder != null) {
                            mediaRecorder.release();
                            mediaRecorder = null;
                        }
                    }
                } else {
                    // Stop native recording
                    if (mediaRecorder != null && isRecording) {
                        try {
                            mediaRecorder.stop();
                            isRecording = false;
                            Log.d(TAG, "Native recording stopped at: " + currentRecordingPath);
                        } catch (RuntimeException e) {
                            if (e.getMessage().contains("stop failed.")) {
                            Log.w(TAG, "Stop failed (-1007 IO error) - forcing release, possible empty clip");
                            } else {
                            throw e;
                            }
                            isRecording = false;
                        }

                        // Always try Base64 if file exists
                        File audioFile = new File(currentRecordingPath);
                        if (audioFile.exists()) {
                            long fileSize = audioFile.length();
                            Log.d(TAG, "Clip file size: " + fileSize + " bytes");
                            if (fileSize > 0) {
                            byte[] audioBytes = new byte[(int) audioFile.length()];
                            try (FileInputStream fis = new FileInputStream(currentRecordingPath)) {
                            fis.read(audioBytes);
                            } catch (IOException e) {
                            Log.e(TAG, "IO error reading clip file: " + e.getMessage(), e);
                            return;  // Skip Base64 if IO fails
                            }
                            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
                            localIntent.putExtra("audioBase64", base64Audio);
                            localIntent.putExtra("audioPath", currentRecordingPath);
                            localIntent.putExtra("mimeType", "audio/aac");
                            long startTime = intent.getLongExtra("timestamp", 0);
                            localIntent.putExtra("durationMs", System.currentTimeMillis() - startTime);
                            Log.d(TAG, "Clip processed: " + audioBytes.length + " bytes");
                            } else {
                            Log.w(TAG, "Empty clip file - skipping Base64");
                            }
                        } else {
                            Log.w(TAG, "No clip file created");
                        }
                    } else {
                    Log.w(TAG, "Not recording - no clip to process");
                    }

                    if (mediaRecorder != null) {
                    mediaRecorder.release();
                    mediaRecorder = null;
                    }

                    // Release focus/wake
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest);
                    } else {
                        audioManager.abandonAudioFocus(null);
                    }
                    if (wakeLock.isHeld()) {
                        wakeLock.release();
                        Log.d(TAG, "Wake lock released");
                    }
                }

                sendBroadcast(localIntent);
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
            .setContentTitle("PTT ativo")
            .setContentText("Pressione o botão lateral para falar")
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