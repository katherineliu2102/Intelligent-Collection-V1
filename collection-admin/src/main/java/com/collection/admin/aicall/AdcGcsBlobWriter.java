package com.collection.admin.aicall;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.FileInputStream;
import java.io.IOException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 录音写入用独立服务账号 json（{@code collection.aicall.recording-credentials}），不碰 Pub/Sub 的 {@code
 * GOOGLE_APPLICATION_CREDENTIALS}。
 */
@Component
public class AdcGcsBlobWriter implements GcsBlobWriter {

    private final AicallRecordingProperties properties;

    public AdcGcsBlobWriter(AicallRecordingProperties properties) {
        this.properties = properties;
    }

    @Override
    public void write(String bucket, String objectName, byte[] bytes, String contentType) {
        String credPath = properties == null ? null : properties.getRecordingCredentials();
        if (StringUtils.isBlank(credPath)) {
            throw new IllegalStateException("recording credentials path empty");
        }
        Storage storage = storageFor(credPath.trim());
        BlobInfo info =
                BlobInfo.newBuilder(bucket, objectName)
                        .setContentType(contentType == null ? "audio/wav" : contentType)
                        .build();
        storage.create(info, bytes);
    }

    static Storage storageFor(String credPath) {
        try (FileInputStream in = new FileInputStream(credPath)) {
            GoogleCredentials creds = ServiceAccountCredentials.fromStream(in);
            return StorageOptions.newBuilder().setCredentials(creds).build().getService();
        } catch (IOException e) {
            throw new IllegalStateException("load recording credentials failed", e);
        }
    }
}
