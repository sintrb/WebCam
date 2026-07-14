package com.sintrb.webcam;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {
    private static final String PREFS = "webcam_settings";
    private static final String KEY_HTTP_PORT = "http_port";
    private static final String KEY_IMAGE_SIZE = "image_size";
    private static final String KEY_ROTATION = "rotation";
    private static final String KEY_AUTO_FOCUS = "auto_focus";
    private static final String KEY_WATERMARK_POSITION = "watermark_position";

    public static final int DEFAULT_HTTP_PORT = 8888;
    public static final String DEFAULT_IMAGE_SIZE = "720x1280";
    public static final int DEFAULT_ROTATION = 90;
    public static final boolean DEFAULT_AUTO_FOCUS = true;
    public static final String DEFAULT_WATERMARK_POSITION = "right_bottom";

    public static final String[] IMAGE_SIZE_OPTIONS = new String[] {
            "原图",
            "480x640",
            "720x1280",
            "1080x1440",
            "1080x1920",
            "1440x1920"
    };

    public static final int[] ROTATION_OPTIONS = new int[] {0, 90, 180, 270};
    public static final String[] WATERMARK_POSITION_VALUES = new String[] {
            "none",
            "left_top",
            "left_bottom",
            "right_top",
            "right_bottom"
    };
    public static final String[] WATERMARK_POSITION_LABELS = new String[] {
            "无",
            "左上角",
            "左下角",
            "右上角",
            "右下角"
    };

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getHttpPort(Context context) {
        return sanitizePort(prefs(context).getInt(KEY_HTTP_PORT, DEFAULT_HTTP_PORT));
    }

    public static void setHttpPort(Context context, int port) {
        prefs(context).edit().putInt(KEY_HTTP_PORT, sanitizePort(port)).apply();
    }

    public static String getDefaultImageSize(Context context) {
        return prefs(context).getString(KEY_IMAGE_SIZE, DEFAULT_IMAGE_SIZE);
    }

    public static void setDefaultImageSize(Context context, String value) {
        prefs(context).edit().putString(KEY_IMAGE_SIZE, value).apply();
    }

    public static int getDefaultRotation(Context context) {
        return sanitizeRotation(prefs(context).getInt(KEY_ROTATION, DEFAULT_ROTATION));
    }

    public static void setDefaultRotation(Context context, int value) {
        prefs(context).edit().putInt(KEY_ROTATION, sanitizeRotation(value)).apply();
    }


    public static boolean isAutoFocusEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_FOCUS, DEFAULT_AUTO_FOCUS);
    }

    public static void setAutoFocusEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_FOCUS, enabled).apply();
    }

    public static String getWatermarkPosition(Context context) {
        return sanitizeWatermarkPosition(prefs(context).getString(KEY_WATERMARK_POSITION, DEFAULT_WATERMARK_POSITION));
    }

    public static void setWatermarkPosition(Context context, String value) {
        prefs(context).edit().putString(KEY_WATERMARK_POSITION, sanitizeWatermarkPosition(value)).apply();
    }

    public static SizeOption parseSize(String value) {
        if (value == null || value.trim().isEmpty() || "原图".equals(value)) {
            return new SizeOption(0, 0, "原图");
        }
        String[] parts = value.toLowerCase().split("x");
        if (parts.length == 2) {
            try {
                int width = Integer.parseInt(parts[0].trim());
                int height = Integer.parseInt(parts[1].trim());
                return new SizeOption(width, height, value);
            } catch (Exception ignored) {
            }
        }
        return new SizeOption(0, 0, "原图");
    }

    public static int sanitizePort(int port) {
        if (port < 1 || port > 65535) {
            return DEFAULT_HTTP_PORT;
        }
        return port;
    }

    public static int sanitizeRotation(int rotation) {
        switch (rotation) {
            case 0:
            case 90:
            case 180:
            case 270:
                return rotation;
            default:
                return DEFAULT_ROTATION;
        }
    }

    public static String sanitizeWatermarkPosition(String value) {
        if (value != null) {
            for (String option : WATERMARK_POSITION_VALUES) {
                if (option.equals(value)) {
                    return value;
                }
            }
        }
        return DEFAULT_WATERMARK_POSITION;
    }

    public static String getWatermarkLabel(String value) {
        String normalized = sanitizeWatermarkPosition(value);
        for (int i = 0; i < WATERMARK_POSITION_VALUES.length; i++) {
            if (WATERMARK_POSITION_VALUES[i].equals(normalized)) {
                return WATERMARK_POSITION_LABELS[i];
            }
        }
        return WATERMARK_POSITION_LABELS[WATERMARK_POSITION_LABELS.length - 1];
    }

    public static class SizeOption {
        public final int width;
        public final int height;
        public final String label;

        public SizeOption(int width, int height, String label) {
            this.width = width;
            this.height = height;
            this.label = label;
        }
    }
}
