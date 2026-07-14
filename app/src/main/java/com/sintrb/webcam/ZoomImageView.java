package com.sintrb.webcam;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

public class ZoomImageView extends AppCompatImageView {
    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final PointF last = new PointF();
    private boolean dragging;
    private float minScale = 1f;
    private float maxScale = 5f;

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float current = getCurrentScale();
                float target = current > 1.5f ? 1f : 2f;
                float factor = target / current;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                fixBounds();
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                last.set(event.getX(), event.getY());
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && dragging) {
                    float dx = event.getX() - last.x;
                    float dy = event.getY() - last.y;
                    matrix.postTranslate(dx, dy);
                    fixBounds();
                    setImageMatrix(matrix);
                    last.set(event.getX(), event.getY());
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitCenter();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitCenter);
    }

    private void fitCenter() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        matrix.reset();
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        float scale = Math.min(viewWidth / drawableWidth, viewHeight / drawableHeight);
        minScale = scale;
        float dx = (viewWidth - drawableWidth * scale) / 2f;
        float dy = (viewHeight - drawableHeight * scale) / 2f;
        matrix.postScale(scale, scale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    private float getCurrentScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void fixBounds() {
        Drawable drawable = getDrawable();
        if (drawable == null) {
            return;
        }
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        if (scale < minScale) {
            float factor = minScale / scale;
            matrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
            matrix.getValues(values);
        } else if (scale > maxScale) {
            float factor = maxScale / scale;
            matrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
            matrix.getValues(values);
        }

        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float width = drawable.getIntrinsicWidth() * values[Matrix.MSCALE_X];
        float height = drawable.getIntrinsicHeight() * values[Matrix.MSCALE_Y];

        float minX = width > getWidth() ? getWidth() - width : (getWidth() - width) / 2f;
        float maxX = width > getWidth() ? 0 : (getWidth() - width) / 2f;
        float minY = height > getHeight() ? getHeight() - height : (getHeight() - height) / 2f;
        float maxY = height > getHeight() ? 0 : (getHeight() - height) / 2f;

        float fixX = 0;
        float fixY = 0;
        if (transX < minX) fixX = minX - transX;
        if (transX > maxX) fixX = maxX - transX;
        if (transY < minY) fixY = minY - transY;
        if (transY > maxY) fixY = maxY - transY;
        matrix.postTranslate(fixX, fixY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
            fixBounds();
            setImageMatrix(matrix);
            return true;
        }
    }
}
