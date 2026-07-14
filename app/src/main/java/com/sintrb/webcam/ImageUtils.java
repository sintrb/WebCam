package com.sintrb.webcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class ImageUtils {
    public static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (bitmap == null || degrees % 360 == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    public static Bitmap resizeCenterCropBitmap(Bitmap bitmap, int targetWidth, int targetHeight) {
        if (bitmap == null) return null;
        if (targetWidth <= 0 && targetHeight <= 0) return bitmap;
        if (targetWidth <= 0) targetWidth = Math.max(1, bitmap.getWidth() * targetHeight / bitmap.getHeight());
        if (targetHeight <= 0) targetHeight = Math.max(1, bitmap.getHeight() * targetWidth / bitmap.getWidth());

        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        float scale = Math.max(targetWidth / (float) bitmap.getWidth(), targetHeight / (float) bitmap.getHeight());
        float scaledWidth = bitmap.getWidth() * scale;
        float scaledHeight = bitmap.getHeight() * scale;
        float left = (targetWidth - scaledWidth) / 2f;
        float top = (targetHeight - scaledHeight) / 2f;
        RectF dst = new RectF(left, top, left + scaledWidth, top + scaledHeight);
        canvas.drawBitmap(bitmap, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return output;
    }

    public static void saveJpeg(Bitmap bitmap, File file, int quality) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
        }
    }

    public static Bitmap decodeSampledFile(String path, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(path, options);
    }

    public static int[] readDimensions(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        return new int[] {options.outWidth, options.outHeight};
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (reqWidth <= 0) reqWidth = width;
        if (reqHeight <= 0) reqHeight = height;
        while ((height / inSampleSize) > reqHeight * 2 || (width / inSampleSize) > reqWidth * 2) {
            inSampleSize *= 2;
        }
        return Math.max(1, inSampleSize);
    }
}
