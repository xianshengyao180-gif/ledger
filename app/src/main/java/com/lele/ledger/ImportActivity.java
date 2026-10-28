package com.lele.ledger;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 微信账单 CSV 导入页。
 *
 * 流程：
 * 1. 打开 → 自动弹文件选择器（设定只选 CSV）
 * 2. 选文件后 → 尝试 GBK 编码解析，若明显乱码则退回 UTF-8
 * 3. 解析完后 → 弹结果弹窗：导入 X 笔 / 跳过重复 Y 笔 / 不符条件 Z 笔 / 格式错误 W 笔
 * 4. 确认后 → 关闭本页，主界面自动刷新
 *
 * 文件选择器用标准的 ACTION_OPEN_DOCUMENT，
 * 不需要 READ_EXTERNAL_STORAGE 授权（Android 4.4+ 文档式访问不要求）。
 */
public class ImportActivity extends AppCompatActivity {

    /** 文件选择请求码 */
    private static final int REQUEST_PICK_FILE = 100;

    /**
     * debug 专用：直接传文件系统路径跳文件选择器。
     * 仅用于 adb 端到端验证，正式发布时可删。
     * 传法：adb shell am start -n com.lele.ledger/.ImportActivity
     *   --es debug_path /sdcard/Download/wx_bill_test.csv
     */
    private static final String EXTRA_DEBUG_PATH = "debug_path";

    /** debug build：intent extra `auto_import` = "true" 时从 assets 读取测试 CSV */
    private static final String EXTRA_AUTO_IMPORT = "auto_import";
    private static final String ASSET_TEST_CSV = "wx_bill_test.csv";

    /** intent extra：自动扫描 Download 目录找 .xlsx 微信账单 */
    private static final String EXTRA_AUTO_SCAN = "auto_scan_download";

    private Db db;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import);
        db = new Db(this);
        tvStatus = findViewById(R.id.tvImportStatus);

        // [debug] 从 assets 读取测试 CSV（adb 传 auto_import=true 触发）
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_AUTO_IMPORT, false)) {
            importFromAssets();
            return;
        }

        // [debug] 从 intent extra 传文件系统路径
        String debugPath = getIntent().getStringExtra(EXTRA_DEBUG_PATH);
        if (BuildConfig.DEBUG && debugPath != null && !debugPath.isEmpty()) {
            tvStatus.setText("debug 模式，读取: " + debugPath);
            parseFileAtPath(debugPath);
            return;
        }

        // [debug] 自动扫描 Download 目录找微信账单 xlsx
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_AUTO_SCAN, false)) {
            scanDownloadXlsx();
            return;
        }

        // 从外部应用打开文件：文件管理器/微信长按 xlsx/csv → 本应用
        if (Intent.ACTION_VIEW.equals(getIntent().getAction())) {
            android.net.Uri viewUri = getIntent().getData();
            if (viewUri != null) {
                parseFromUri(viewUri);
                return;
            }
        }

        // 从外部应用分享文件：文件管理器长按 → 分享 → 极简记账
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            android.net.Uri shareUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (shareUri != null) {
                parseFromUri(shareUri);
                return;
            }
        }

        // 正常流程：弹文件选择器
        pickFile();
    }

    /** debug：从 assets 读取测试 CSV 直接验证解析流程。 */
    private void importFromAssets() {
        tvStatus.setText("正在从 assets 读取测试 CSV…");
        CsvImporter.Result r;
        try {
            // 先 GBK（微信 PC 端默认编码）
            java.io.InputStream in = getAssets().open(ASSET_TEST_CSV);
            r = CsvImporter.importCsv(this, in, "GBK");
            in.close();
        } catch (Exception e) {
            toastAndFinish("assets 读取失败: " + e.getMessage());
            return;
        }
        showResult(r);
    }

    /**
     * 扫描 MediaStore Download 目录找 .xlsx 微信账单文件，自动导入。
     * 用 ContentResolver 读取（不需要 READ_EXTERNAL_STORAGE）。
     */
    private void scanDownloadXlsx() {
        tvStatus.setText("正在扫描文件…");
        try {
            // ---------- 方案 A：先检查 getExternalFilesDir ----------
            java.io.File externalFilesDir = getExternalFilesDir(null);
            if (externalFilesDir != null) {
                java.io.File[] files = externalFilesDir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        if (f.getName().toLowerCase(Locale.CHINA).endsWith(".xlsx")) {
                            tvStatus.setText("找到: " + f.getName() + "，正在解析…");
                            InputStream in = new java.io.FileInputStream(f);
                            CsvImporter.Result r = ExcelImporter.importExcel(this, in);
                            in.close();
                            showResult(r);
                            return;
                        }
                    }
                }
            }

            // ---------- 方案 B：MediaStore Downloads ----------
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.net.Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    android.provider.MediaStore.MediaColumns._ID,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME
            };
            String selection = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] selArgs = {"%xlsx%"};

            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(collection, projection, selection, selArgs,
                        android.provider.MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            } catch (Exception ignored) {}

            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (name != null && name.toLowerCase(Locale.CHINA).endsWith(".xlsx")) {
                            android.net.Uri fileUri = android.net.Uri.withAppendedPath(
                                    collection, String.valueOf(c.getLong(0)));
                            tvStatus.setText("找到: " + name + "，正在解析…");
                            InputStream in = getContentResolver().openInputStream(fileUri);
                            if (in != null) {
                                CsvImporter.Result r = ExcelImporter.importExcel(this, in);
                                in.close();
                                showResult(r);
                                return;
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }

            }
            toastAndFinish("没有找到可导入的 .xlsx 文件");
        } catch (Exception e) {
            toastAndFinish("扫描失败: " + e.getMessage());
        }
    }

    /**
     * debug 模式：直接读取文件系统路径（不经过文件选择器）。
     * 用于 adb 端到端验证解析逻辑。
     * 注意：从 Android 11 起，普通 app 无权直接读 /sdcard 目录树下的文件，
     * 但 readfile 权限仍然在前台时有效。
     */
    private void parseFileAtPath(String path) {
        CsvImporter.Result r;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) {
                toastAndFinish("debug 文件不存在: " + path);
                return;
            }
            tvStatus.setText("正在解析: " + f.getName());
            // 先 GBK，几乎拿不到结果则退回 UTF-8
            InputStream in = new java.io.FileInputStream(f);
            r = CsvImporter.importCsv(this, in, "GBK");
            in.close();
            if (r.imported == 0 && r.invalid > 0 && r.invalid + r.skipped > 3) {
                InputStream in2 = new java.io.FileInputStream(f);
                CsvImporter.Result r2 = CsvImporter.importCsv(this, in2, "UTF-8");
                in2.close();
                r = r2;
            }
        } catch (Exception e) {
            toastAndFinish("读取失败: " + e.getMessage());
            return;
        }
        showResult(r);
    }

    /**
     * 打开系统文件选择器，过滤为 CSV 和纯文本。
     *
     * 用 ACTION_OPEN_DOCUMENT 而非 ACTION_GET_CONTENT：
     * 前者支持持久化 URI 授权，后者不需要。
     * 这里只是一次性读取，GET_CONTENT 就够了且更广泛兼容。
     */
    /**
     * 处理从外部应用打开的 URI（文件管理器长按 xlsx/csv → 用本应用打开）。
     * 直接解析并 Toast 结果，解析完自动关闭。
     */
    private void parseFromUri(android.net.Uri uri) {
        String fileName = getFileName(uri);
        // 通过文件名或 MIME 类型判断是不是 xlsx
        boolean isXlsx = (fileName != null && fileName.toLowerCase(Locale.CHINA).endsWith(".xlsx"))
                || "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        .equals(getContentResolver().getType(uri));
        CsvImporter.Result r;
        try {
            // 先用 ContentResolver 读（适配 content:// URI，文件管理器真实场景）
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
            } catch (Exception ignored) {}
            // fallback：如果是 file:// URI，直接用 FileInputStream
            if (in == null && "file".equals(uri.getScheme())) {
                try {
                    in = new java.io.FileInputStream(uri.getPath());
                } catch (Exception ignored) {}
            }
            if (in == null) {
                Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            if (isXlsx) {
                r = ExcelImporter.importExcel(this, in);
            } else {
                // CSV：先 GBK 再 UTF-8
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                in.close();
                byte[] all = baos.toByteArray();
                r = CsvImporter.importCsv(this, new java.io.ByteArrayInputStream(all), "GBK");
                if (r.invalid > all.length / 10) {
                    r = CsvImporter.importCsv(this, new java.io.ByteArrayInputStream(all), "UTF-8");
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "解析失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        showResult(r);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        // 支持 CSV 和 xlsx 两种格式
        String[] mimeTypes = {"*/*"};
        i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        // 帮助用户在微信文件中选择器不迷路：提示词
        i.putExtra(Intent.EXTRA_TITLE, "选择微信导出的账单(CSV 或 xlsx)");
        try {
            startActivityForResult(i, REQUEST_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "您的设备上没有文件选择器可用", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILE) return;

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Uri uri = data.getData();

        // ---- 取文件名 ----
        String fileName = getFileName(uri);
        boolean isXlsx = fileName != null && fileName.toLowerCase(Locale.CHINA).endsWith(".xlsx");
        if (fileName != null && !fileName.isEmpty()) {
            tvStatus.setText("正在解析: " + fileName);
        } else {
            tvStatus.setText("正在解析文件…");
        }

        // ---- 根据文件类型路由解析器 ----
        CsvImporter.Result r;
        try {
            if (isXlsx) {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    toastAndFinish("无法读取文件");
                    return;
                }
                r = ExcelImporter.importExcel(this, in);
                in.close();
            } else {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    toastAndFinish("无法读取文件");
                    return;
                }
                r = CsvImporter.importCsv(this, in, "GBK");
                in.close();
                if (r.imported == 0 && r.invalid > 0 && r.invalid + r.skipped > 3) {
                    InputStream in2 = getContentResolver().openInputStream(uri);
                    if (in2 != null) {
                        CsvImporter.Result r2 = CsvImporter.importCsv(this, in2, "UTF-8");
                        in2.close();
                        r = r2;
                    }
                }
            }
        } catch (Exception e) {
            toastAndFinish("文件读取失败: " + e.getMessage());
            return;
        }

        // ---- 显示结果 ----
        showResult(r);
    }

    /** 从 content URI 获取文件名（通常文件选择器都返回，但不保证）。 */
    private String getFileName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        name = c.getString(idx);
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    /** 弹结果弹窗，关闭后 finish()。 */
    private void showResult(CsvImporter.Result r) {
        StringBuilder msg = new StringBuilder();
        msg.append("✅ 成功导入: ").append(r.imported).append(" 笔\n");
        if (r.duplicate > 0) {
            msg.append("⏭ 已存在跳过: ").append(r.duplicate).append(" 笔\n");
        }
        if (r.skipped > 0) {
            msg.append("⚪ 未到账跳过: ").append(r.skipped).append(" 笔\n");
        }
        if (r.invalid > 0) {
            msg.append("❌ 格式错误: ").append(r.invalid).append(" 笔\n");
        }

        // 有错误时展开详情
        if (!r.errors.isEmpty() && r.errors.size() <= 10) {
            msg.append("\n异常详情：\n");
            for (String e : r.errors) {
                msg.append("• ").append(e).append("\n");
            }
        } else if (r.errors.size() > 10) {
            msg.append("\n异常详情（仅前 10 条）：\n");
            for (int i = 0; i < 10; i++) {
                msg.append("• ").append(r.errors.get(i)).append("\n");
            }
            msg.append("…共 ").append(r.errors.size()).append(" 条异常\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("导入结果")
                .setMessage(msg.toString())
                .setPositiveButton("完成", (d, w) -> {
                    // 通知主界面刷新
                    sendBroadcast(new Intent(MainActivity.ACTION_BILLS_CHANGED).setPackage(getPackageName()));
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void toastAndFinish(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (db != null) db.close();
        super.onDestroy();
    }
}
