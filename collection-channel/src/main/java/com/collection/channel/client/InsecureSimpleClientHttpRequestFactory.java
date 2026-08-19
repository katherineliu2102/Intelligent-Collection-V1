package com.collection.channel.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 仅用于 Facade 联调：信任对方自签名证书，且不改 JVM 全局默认 SSL。 生产必须使用校验完整的 {@code RestTemplate}。
 */
public final class InsecureSimpleClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

    private final SSLSocketFactory socketFactory;

    public InsecureSimpleClientHttpRequestFactory() {
        try {
            TrustManager[] trustAll =
                    new TrustManager[] {
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                    };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            this.socketFactory = context.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init insecure TLS factory", e);
        }
    }

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod)
            throws IOException {
        super.prepareConnection(connection, httpMethod);
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(socketFactory);
            https.setHostnameVerifier((hostname, session) -> true);
        }
    }
}
