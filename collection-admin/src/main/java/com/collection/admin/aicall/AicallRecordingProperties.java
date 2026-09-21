package com.collection.admin.aicall;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI Call 录音进 GCS。空桶则夜间任务跳过。写入用独立 SA json，不复用 Pub/Sub ADC。 */
@Data
@Component
@ConfigurationProperties(prefix = "collection.aicall")
public class AicallRecordingProperties {

    /** GCS bucket，不含 gs://。 */
    private String recordingBucket = "";

    /** 对象前缀，不含首尾 /。现网 {@code intelligent-collection}。 */
    private String recordingPrefix = "";

    /** 服务账号 json 绝对路径；空则跳过写入。 */
    private String recordingCredentials = "";
}
