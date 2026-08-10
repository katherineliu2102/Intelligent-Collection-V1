package com.collection.channel.client;

import java.net.ConnectException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

/**
 * HTTP 故障分类：判定请求字节是否<b>可证明未写给供应商</b>。
 *
 * <p>只有可证明未发出的故障才允许重试——无论渠道侧短重试还是引擎侧退避重试。 读超时、写后连接中断、供应商 5xx 均属结果未知：请求可能已被受理，重试等于重复触达。
 */
public final class HttpFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 10;

    private HttpFailureClassifier() {}

    /**
     * 请求确定未被供应商受理，重试安全。
     *
     * <ul>
     *   <li>HTTP 429：供应商已应答「拒绝受理」，是明确的未发送信号
     *   <li>DNS 解析失败 / 连接被拒 / TLS 握手失败：请求字节未写出
     * </ul>
     *
     * <p><b>Socket 超时不在此列</b>：当前 {@code RestTemplate} 使用 JDK 默认 {@code
     * SimpleClientHttpRequestFactory}，连接超时与读超时都表现为 {@link
     * java.net.SocketTimeoutException}，无法区分，故一律按结果未知处理。 若将来换成 Apache HttpClient，其 {@code
     * ConnectTimeoutException} 可证明未发出——按类名匹配以避免引入可选依赖。
     */
    public static boolean provablyNotSent(Throwable e) {
        if (e instanceof HttpStatusCodeException) {
            return ((HttpStatusCodeException) e).getRawStatusCode() == 429;
        }
        if (!(e instanceof ResourceAccessException)) {
            return false;
        }
        Throwable cause = e.getCause();
        for (int depth = 0;
                cause != null && depth < MAX_CAUSE_DEPTH;
                depth++, cause = cause.getCause()) {
            if (cause instanceof UnknownHostException
                    || cause instanceof ConnectException
                    || cause instanceof SSLHandshakeException
                    || "ConnectTimeoutException".equals(cause.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }
}
