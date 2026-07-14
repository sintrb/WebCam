package com.sintrb.webcam;

import java.io.File;

public class PhotoRecord {
    public final String source;
    public final long timestamp;
    public final File file;
    public final File thumbFile;
    public final int width;
    public final int height;

    public PhotoRecord(String source, long timestamp, File file, File thumbFile, int width, int height) {
        this.source = source;
        this.timestamp = timestamp;
        this.file = file;
        this.thumbFile = thumbFile;
        this.width = width;
        this.height = height;
    }
}
