package com.sintrb.webcam;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PhotoRecordAdapter extends RecyclerView.Adapter<PhotoRecordAdapter.ViewHolder> {
    public interface OnRecordClickListener {
        void onRecordClick(PhotoRecord record);
    }

    private final List<PhotoRecord> items = new ArrayList<>();
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private OnRecordClickListener onRecordClickListener;

    public void setItems(List<PhotoRecord> records) {
        items.clear();
        items.addAll(records);
        notifyDataSetChanged();
    }

    public void setOnRecordClickListener(OnRecordClickListener listener) {
        this.onRecordClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_photo_record, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PhotoRecord record = items.get(position);
        holder.title.setText(record.source);
        holder.subtitle.setText(format.format(new Date(record.timestamp)) + "  ·  " + record.width + "x" + record.height + "\n" + record.file.getName());
        Bitmap bitmap = ImageUtils.decodeSampledFile(record.thumbFile.getAbsolutePath(), 140, 140);
        holder.thumb.setImageBitmap(bitmap);

        View.OnClickListener openListener = v -> {
            if (onRecordClickListener != null) {
                onRecordClickListener.onRecordClick(record);
            }
        };
        holder.card.setOnClickListener(openListener);
        holder.content.setOnClickListener(openListener);
        holder.thumb.setOnClickListener(openListener);
        holder.title.setOnClickListener(openListener);
        holder.subtitle.setOnClickListener(openListener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final View content;
        final ImageView thumb;
        final TextView title;
        final TextView subtitle;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardRecord);
            content = itemView.findViewById(R.id.recordContent);
            thumb = itemView.findViewById(R.id.imageThumb);
            title = itemView.findViewById(R.id.tvTitle);
            subtitle = itemView.findViewById(R.id.tvSubtitle);
        }
    }
}
