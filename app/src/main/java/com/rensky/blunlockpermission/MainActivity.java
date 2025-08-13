package com.rensky.blunlockpermission; // Your package name

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.rensky.blunlockpermission.databinding.ActivityMainBinding;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String URL = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth";
    private static final long RETRY_DELAY_MS = 250;

    private ActivityMainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();

    private final AtomicBoolean isScheduledProcessRunning = new AtomicBoolean(false);
    private final AtomicBoolean isManualProcessRunning = new AtomicBoolean(false);

    // --- NEW: Components for the live clock ---
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

        // --- NEW: Start the live clock ---
        startLiveClock();
    }

    private void startLiveClock() {
        // Formatter for HH:MM:SS.ms
        final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        clockRunnable = new Runnable() {
            @Override
            public void run() {
                if (binding != null) {
                    // Update the clock TextView with the current time
                    binding.clockTextView.setText(ZonedDateTime.now().format(timeFormatter));
                }
                // Schedule this to run again in 100 milliseconds
                clockHandler.postDelayed(this, 100);
            }
        };
        // Start the runnable immediately
        clockHandler.post(clockRunnable);
    }
    
    // --- The rest of your methods remain the same ---

    private void startScheduledProcess() {
        String cookie = getCookieAndValidate();
        if (cookie == null) return;

        isScheduledProcessRunning.set(true);
        binding.scheduledToggleButton.setText("Stop Scheduled");
        addLog("Scheduled process started. Waiting for target time...");

        executor.submit(() -> {
            waitForTargetTime();
            if (isScheduledProcessRunning.get()) {
                startContinuousAttempts(cookie, isScheduledProcessRunning, "Scheduled");
            }
        });
    }

    private void stopScheduledProcess() {
        isScheduledProcessRunning.set(false);
        binding.scheduledToggleButton.setText("Start Scheduled");
        addLog("\nScheduled process manually stopped by user.");
    }

    private void startManualProcess() {
        String cookie = getCookieAndValidate();
        if (cookie == null) return;

        isManualProcessRunning.set(true);
        binding.manualToggleButton.setText("Stop Manual");
        addLog("Manual process started.");

        executor.submit(() -> startContinuousAttempts(cookie, isManualProcessRunning, "Manual"));
    }

    private void stopManualProcess() {
        isManualProcessRunning.set(false);
        binding.manualToggleButton.setText("Start Manual");
        addLog("\nManual process manually stopped by user.");
    }
    
    private String getCookieAndValidate() {
        String cookie = binding.cookieEditText.getText().toString().trim();
        if (cookie.isEmpty()) {
            Toast.makeText(this, "Please paste your cookie first", Toast.LENGTH_SHORT).show();
            return null;
        }
        return cookie;
    }

    private void waitForTargetTime() {
        ZoneId indiaZone = ZoneId.of("Asia/Kolkata");
        ZonedDateTime nowIST = ZonedDateTime.now(indiaZone);
        ZonedDateTime targetIST = nowIST.withHour(21).withMinute(29).withSecond(58).withNano(0);
        if (nowIST.isAfter(targetIST)) {
            targetIST = targetIST.plusDays(1);
        }

        addLog("Scheduled to start continuous attempts at: " + targetIST.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));

        while (isScheduledProcessRunning.get()) {
            Duration timeUntilStart = Duration.between(ZonedDateTime.now(indiaZone), targetIST);
            if (timeUntilStart.isNegative() || timeUntilStart.isZero()) {
                addLog("\nTarget time reached!");
                break;
            }
            addLog(String.format("Time until start: %.2f seconds", timeUntilStart.toMillis() / 1000.0), true);
            try {
                Thread.sleep(Math.max(100, Math.min(timeUntilStart.toMillis(), 1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handler.post(this::stopScheduledProcess);
                return;
            }
        }
    }

    private void startContinuousAttempts(String cookie, AtomicBoolean controller, String processType) {
        addLog(String.format("\n--- [%s] Starting Continuous Unlock Attempts ---", processType));
        while (controller.get()) {
            if (sendUnlockRequest(cookie)) {
                addLog(String.format("\n--- [%s] Success! Halting all attempts. ---", processType));
                handler.post(() -> {
                    if (controller == isScheduledProcessRunning) stopScheduledProcess();
                    else if (controller == isManualProcessRunning) stopManualProcess();
                });
                break;
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean sendUnlockRequest(String cookie) {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        RequestBody body = RequestBody.create("{\"is_retry\": true}", JSON);
        Request request = new Request.Builder().url(URL).post(body).header("User-Agent", "okhttp/4.12.0").header("Cookie", cookie).build();
        long startTime = System.nanoTime();
        addLog(String.format("[%s] Sending request...", ZonedDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))));

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
                
                if (code != 0) {
                    addLog(String.format("❌ API ERROR - Code: %d (%s). (Duration: %d ms).", code, jsonObject.optString("msg", "No message"), durationMs));
                    return false;
                }

                if (msg.contains("login") || msg.contains("cookie") || msg.contains("auth")) {
                    addLog(String.format("❌ AUTH ERROR - Invalid Cookie? (Duration: %d ms). Message: %s", durationMs, jsonObject.optString("msg")));
                    return false;
                }

                JSONObject data = jsonObject.optJSONObject("data");
                if (data != null) {
                    int applyResult = data.optInt("apply_result", -1);
                    if (applyResult == 1) {
                        addLog(String.format("✅ SUCCESS! (Duration: %d ms). Permission granted.", durationMs));
                        return true;
                    } else if (applyResult == 3) {
                        addLog(String.format("❌ FAILED - Permission not granted. (Duration: %d ms). Trying again...", durationMs));
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
        // --- NEW: Stop the clock when the app is destroyed ---
        if (clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }
        isScheduledProcessRunning.set(false);
        isManualProcessRunning.set(false);
        executor.shutdownNow();
        this.binding = null;
    }
}
