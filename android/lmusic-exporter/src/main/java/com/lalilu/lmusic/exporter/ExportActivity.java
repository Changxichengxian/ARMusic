package com.lalilu.lmusic.exporter;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ExportActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.armusic";
    private static final String TARGET_ACTIVITY = "com.lalilu.lmusic.migration.ARMusicMigrationImportActivity";
    private static final String TARGET_ACTION = "com.armusic.IMPORT_BACKUP";

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setTextSize(16f);
        statusView.setPadding(32, 32, 32, 32);
        statusView.setText("正在导出 LMusic 数据...");
        setContentView(statusView);

        new Thread(this::exportAndSend).start();
    }

    private void exportAndSend() {
        try {
            ExportResult result = exportBackup();
            runOnUiThread(() -> {
                statusView.setText("已导出：" + result.summary + "\n" + result.outputFile.getAbsolutePath());
                sendToARMusic(result.outputFile);
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                String message = "导出失败：" + error.getMessage();
                statusView.setText(message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }
    }

    private ExportResult exportBackup() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject sharedPreferences = exportSharedPreferences();
        JSONArray histories = exportHistories();

        root.put("format", "armusic-backup-v1");
        root.put("version", 1);
        root.put("sourcePackage", getPackageName());
        root.put("createdAt", System.currentTimeMillis());
        root.put("sharedPreferences", sharedPreferences);
        root.put("histories", histories);

        File dir = getExternalFilesDir(null);
        if (dir == null) {
            throw new IllegalStateException("外部文件目录不可用");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建导出目录");
        }

        File output = new File(dir, "lmusic-alpha-export.json");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(output, false),
                StandardCharsets.UTF_8
        )) {
            writer.write(root.toString());
        }

        String summary = sharedPreferences.length() + " 个设置文件，" + histories.length() + " 条播放历史";
        return new ExportResult(output, summary);
    }

    private JSONObject exportSharedPreferences() throws JSONException {
        JSONObject result = new JSONObject();
        File prefsDir = new File(getApplicationInfo().dataDir, "shared_prefs");
        Set<String> names = new HashSet<>();

        File[] files = prefsDir.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                String name = file.getName();
                names.add(name.substring(0, name.length() - 4));
            }
        }

        for (String name : names) {
            SharedPreferences prefs = getSharedPreferences(name, MODE_PRIVATE);
            JSONObject item = new JSONObject();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                item.put(entry.getKey(), encodePreferenceValue(entry.getValue()));
            }
            result.put(name, item);
        }

        return result;
    }

    private JSONObject encodePreferenceValue(Object value) throws JSONException {
        JSONObject item = new JSONObject();
        if (value instanceof Integer) {
            item.put("type", "int");
            item.put("value", value);
        } else if (value instanceof Long) {
            item.put("type", "long");
            item.put("value", value);
        } else if (value instanceof Float) {
            item.put("type", "float");
            item.put("value", value);
        } else if (value instanceof Boolean) {
            item.put("type", "boolean");
            item.put("value", value);
        } else if (value instanceof String) {
            item.put("type", "string");
            item.put("value", value);
        } else if (value instanceof Set<?>) {
            JSONArray array = new JSONArray();
            for (Object entry : (Set<?>) value) {
                if (entry != null) array.put(entry.toString());
            }
            item.put("type", "string_set");
            item.put("value", array);
        } else {
            item.put("type", "string");
            item.put("value", value == null ? "" : value.toString());
        }
        return item;
    }

    private JSONArray exportHistories() {
        JSONArray result = new JSONArray();
        File dbFile = getDatabasePath("lmedia.db");
        if (!dbFile.exists() || !dbFile.canRead()) {
            return result;
        }

        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READONLY
        ); Cursor cursor = db.rawQuery("SELECT * FROM m_history", null)) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", 0L);
                item.put("contentId", getString(cursor, "contentId", ""));
                item.put("contentTitle", getString(cursor, "contentTitle", ""));
                item.put("parentId", getString(cursor, "parentId", ""));
                item.put("parentTitle", getString(cursor, "parentTitle", ""));
                item.put("duration", getLong(cursor, "duration", -1L));
                item.put("repeatCount", getInt(cursor, "repeatCount", 0));
                item.put("startTime", getLong(cursor, "startTime", 0L));
                result.put(item);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private String getString(Cursor cursor, String column, String fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return fallback;
        return cursor.getString(index);
    }

    private long getLong(Cursor cursor, String column, long fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return fallback;
        return cursor.getLong(index);
    }

    private int getInt(Cursor cursor, String column, int fallback) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return fallback;
        return cursor.getInt(index);
    }

    private void sendToARMusic(File outputFile) {
        Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".bridge-files",
                outputFile
        );

        Intent intent = new Intent(TARGET_ACTION)
                .setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
                .setDataAndType(uri, "application/json")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        grantUriPermission(TARGET_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
            Toast.makeText(this, "已发送给 ARMusic 导入", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "ARMusic 接收入口不存在，导出文件已保存", Toast.LENGTH_LONG).show();
        }
    }

    private static class ExportResult {
        final File outputFile;
        final String summary;

        ExportResult(File outputFile, String summary) {
            this.outputFile = outputFile;
            this.summary = summary;
        }
    }
}
