package com.rensky.blunlockpermission;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.rensky.blunlockpermission.databinding.ActivityMainBinding;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    // --- Constants ---
    private static final String API_URL = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth";
    private static final String API_HOST = "sgp-api.buy.mi.com";
    private static final List<String> NTP_SERVERS = Arrays.asList(
            "time.google.com", "time.cloudflare.com", "pool.ntp.org", "ntp.aliyun.com"
    );
    private static final int PING_COUNT = 3;
    private static final int PING_TIMEOUT_MS = 2000;
    private static final long DEFAULT_PING_MS = 180;
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    // MODIFIED: Short sleep interval for the waiting loop.
    private static final long WAIT_LOOP_INTERVAL_MS = 100;

    // --- UI and Threading ---
    private ActivityMainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();
    private final AtomicBoolean isScheduledProcessRunning = new AtomicBoolean(false);
    private final AtomicBoolean isManualProcessRunning = new AtomicBoolean(false);
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable clockRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.scheduledToggleButton.setOnClickListener(v -> {
            if (isScheduledProcessRunning.get()) {
                stopScheduledProcess();
            } else {
                startScheduledProcess();
            }
        });

        binding.manualToggleButton.setOnClickListener(v -> {
            if (isManualProcessRunning.get()) {
                stopManualProcess();
            } else {
                startManualProcess();
            }
        });
        startLiveClock();
    }
    
    // ... (startLiveClock, Process Control methods, getCookieAndValidate remain the same) ...
        private void startLiveClock() {
        final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                if (binding != null) {
                    binding.clockTextView.setText(ZonedDateTime.now().format(timeFormatter));
                }
                clockHandler.postDelayed(this, 100);
            }
        };
        clockHandler.post(clockRunnable);
    }

    // --- Process Control ---

    private void startScheduledProcess() {
        String cookie = getCookieAndValidate();
        if (cookie == null) return;

        isScheduledProcessRunning.set(true);
        binding.scheduledToggleButton.setText("Stop Scheduled");
        addLog("Scheduled process started. Calculating precise send time...");

        executor.submit(() -> {
            boolean timingSuccess = waitForTargetTimeWithNtpAndPing();

            if (timingSuccess && isScheduledProcessRunning.get()) {
                executeSingleRequest(cookie, isScheduledProcessRunning, "Scheduled");
            } else if (!timingSuccess) {
                addLog("Process halted due to timing calculation failure or cancellation.");
                handler.post(this::stopScheduledProcess);
            }
        });
    }

    private void stopScheduledProcess() {
        isScheduledProcessRunning.set(false);
        binding.scheduledToggleButton.setText("Start Scheduled");
        addLog("\nScheduled process manually stopped by user or has completed.");
    }

    private void startManualProcess() {
        String cookie = getCookieAndValidate();
        if (cookie == null) return;
        isManualProcessRunning.set(true);
        binding.manualToggleButton.setText("Stop Manual");
        addLog("Manual process started for a single request.");
        executor.submit(() -> executeSingleRequest(cookie, isManualProcessRunning, "Manual"));
    }

    private void stopManualProcess() {
        isManualProcessRunning.set(false);
        binding.manualToggleButton.setText("Start Manual");
        addLog("\nManual process manually stopped by user or has completed.");
    }

    private String getCookieAndValidate() {
        String cookie = binding.cookieEditText.getText().toString();
        if (cookie.isEmpty()) {
            Toast.makeText(this, "Please paste your cookie first", Toast.LENGTH_SHORT).show();
            return null;
        }
        return cookie;
    }


    // --- Precise Timing Logic ---

    /**
     * MODIFIED: Implements an iterative, non-blocking wait.
     * @return true if the wait completed successfully, false if it was cancelled.
     */
    private boolean waitForTargetTimeWithNtpAndPing() {
        long ntpTimeMs = getNtpTime();
        if (ntpTimeMs == -1) return false;

        long averagePingMs = getAveragePing(API_HOST, PING_COUNT);

        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(BEIJING_ZONE));
        calendar.setTimeInMillis(ntpTimeMs);
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long targetArrivalTimestamp = calendar.getTimeInMillis();

        long sendTimestamp = targetArrivalTimestamp - averagePingMs;

        final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z");
        addLog("\n--- Timing Calculation ---");
        addLog("Target Arrival Time: " + ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(targetArrivalTimestamp), BEIJING_ZONE).format(dtf));
        addLog("Calculated Send Time: " + ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(sendTimestamp), BEIJING_ZONE).format(dtf));

        long waitDurationMs = sendTimestamp - ntpTimeMs;
        if (waitDurationMs <= 0) {
            addLog("❌ ERROR: Calculated send time is in the past. Check system clock or network.");
            return false;
        }

        addLog(String.format("Waiting for %.3f seconds to reach send time...", waitDurationMs / 1000.0));

        // --- MODIFIED: Iterative Wait Loop ---
        long endTime = ntpTimeMs + waitDurationMs;
        while (System.currentTimeMillis() < endTime) {
            // Check for cancellation signal.
            if (!isScheduledProcessRunning.get()) {
                addLog("Wait cancelled by user.");
                return false;
            }
            try {
                // Sleep for a short interval.
                Thread.sleep(WAIT_LOOP_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                addLog("Wait interrupted.");
                return false;
            }
        }
        
        if (isScheduledProcessRunning.get()) {
            addLog("\nTarget time reached! Starting unlock attempt.");
        }
        
        return true;
    }

    // ... (getNtpTime, getAveragePing, API methods, logging, and cleanup are the same) ...
        private long getNtpTime() {
        NTPUDPClient ntpClient = new NTPUDPClient();
        ntpClient.setDefaultTimeout(5000);

        for (String server : NTP_SERVERS) {
            addLog("Querying NTP server: " + server);
            try {
                InetAddress hostAddr = InetAddress.getByName(server);
                TimeInfo timeInfo = ntpClient.getTime(hostAddr);
                long ntpTime = timeInfo.getMessage().getTransmitTimeStamp().getTime();

                final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z");
                String formattedNtpTime = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ntpTime), BEIJING_ZONE).format(dtf);
                addLog("✅ NTP time synchronized successfully from " + server);
                addLog("Synchronized Time: " + formattedNtpTime);

                return ntpTime; // Return on first success
            } catch (IOException e) {
                addLog("❌ Failed to get time from " + server + ": " + e.getMessage());
                // Continue to the next server in the list
            }
        }

        addLog("❌ All NTP servers failed. Aborting scheduled process.");
        return -1; // Return -1 if all servers failed
    }
    
    private long getAveragePing(String host, int count) {
        addLog(String.format("Pinging %s %d times...", host, count));
        long totalDuration = 0;
        int successfulPings = 0;

        for (int i = 0; i < count; i++) {
            if (!isScheduledProcessRunning.get()) break;
            try {
                long startTime = System.nanoTime();
                InetAddress.getByName(host).isReachable(PING_TIMEOUT_MS);
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                addLog(String.format("Ping %d: %d ms", i + 1, duration));
                totalDuration += duration;
                successfulPings++;
            } catch (IOException e) {
                addLog(String.format("Ping %d: Failed (%s)", i + 1, e.getMessage()));
            }
        }

        if (successfulPings > 0) {
            long avg = totalDuration / successfulPings;
            addLog(String.format("Average Ping: %d ms", avg));
            return avg;
        } else {
            addLog(String.format("All pings failed. Using default latency: %d ms", DEFAULT_PING_MS));
            return DEFAULT_PING_MS;
        }
    }

    // --- Core API Interaction ---

    private void executeSingleRequest(String cookie, AtomicBoolean controller, String processType) {
        addLog(String.format("\n--- [%s] Sending Single Unlock Request ---", processType));
        if (!controller.get()) {
            return;
        }

        boolean success = sendUnlockRequest(cookie);

        handler.post(() -> {
            if (success) {
                addLog(String.format("\n--- [%s] Process Finished: SUCCESS! ---", processType));
            } else {
                addLog(String.format("\n--- [%s] Process Finished: FAILED. See logs for details. ---", processType));
            }

            if (controller == isScheduledProcessRunning) {
                stopScheduledProcess();
            } else if (controller == isManualProcessRunning) {
                stopManualProcess();
            }
        });
    }

    private boolean sendUnlockRequest(String cookie) {
        addLog(cookie);
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        RequestBody body = RequestBody.create("{\"is_retry\": false}", JSON);
        Request request = new Request.Builder().url(API_URL).post(body).header("User-Agent", "okhttp/4.12.0").header("Cookie", cookie).build();
        long startTime = System.nanoTime();
        addLog(String.format("[%s] Sending request...", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))), true);

        try (Response response = client.newCall(request).execute()) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                addLog(String.format("❌ NETWORK ERROR. (Duration: %d ms). HTTP Code: %d", durationMs, response.code()));
                return false;
            }
            try {
                JSONObject jsonObject = new JSONObject(responseBody);
                int code = jsonObject.optInt("code", -1);
                String msg = jsonObject.optString("msg", "").toLowerCase();

                if (code == 20036) {
                    addLog(String.format("❌ ACCOUNT NOT ELIGIBLE. (Duration: %d ms). Reason: Account is likely less than 30 days old.", durationMs));
                    return false;
                }
                
                if (msg.contains("login") || msg.contains("cookie") || msg.contains("auth")) {
                    addLog(String.format("❌ AUTH ERROR - Invalid Cookie? (Duration: %d ms). Message: %s", durationMs, jsonObject.optString("msg")));
                    return false;
                }

                if (code != 0) {
                    addLog(String.format("❌ API ERROR - Code: %d (%s). (Duration: %d ms).", code, jsonObject.optString("msg", "No message"), durationMs));
                    return false;
                }

                JSONObject data = jsonObject.optJSONObject("data");
                if (data != null) {
                    int applyResult = data.optInt("apply_result", -1);
                    if (applyResult == 1) {
                        addLog(String.format("✅ SUCCESS! (Duration: %d ms). Permission granted.", durationMs));
                        return true;
                    } else if (applyResult == 3) {
                        addLog(String.format("❌ FAILED - Permission not granted. (Duration: %d ms). Result code: 3", durationMs));
                    } else {
                        addLog(String.format("❓ UNKNOWN RESULT. (Duration: %d ms). Response: %s", durationMs, responseBody));
                    }
                } else {
                    addLog(String.format("❓ UNKNOWN - No 'data' object. (Duration: %d ms). Response: %s", durationMs, responseBody));
                }

            } catch (JSONException e) {
                addLog(String.format("❌ JSON EXCEPTION. (Duration: %d ms). Failed to parse server response.", durationMs));
            }

        } catch (IOException e) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            addLog(String.format("❌ CONNECTION EXCEPTION. (Duration: %d ms). Check your internet connection.", durationMs));
        }
        return false;
    }

    // --- Logging and Cleanup ---

    private void addLog(String message, boolean overwrite) {
        handler.post(() -> {
            if (binding == null) return;
            if (overwrite) {
                String currentText = binding.logTextView.getText().toString();
                int lastNewline = currentText.lastIndexOf('\n');
                binding.logTextView.setText(lastNewline != -1 ? currentText.substring(0, lastNewline + 1) + message : message);
            } else {
                binding.logTextView.append(message + "\n");
            }
            binding.logScrollView.post(() -> binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    private void addLog(String message) {
        addLog(message, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }
        isScheduledProcessRunning.set(false);
        isManualProcessRunning.set(false);
        executor.shutdownNow();
        this.binding = null;
    }
}
