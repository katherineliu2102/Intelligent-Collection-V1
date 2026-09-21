package com.collection.admin.aicall;

/** 写 GCS 对象；生产走 ADC，单测 mock。 */
public interface GcsBlobWriter {

    void write(String bucket, String objectName, byte[] bytes, String contentType);
}
