package com.collection.channel.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Facade 媒体下载：禁止跟随 302，避免跳到非 Facade 域名。 */
public final class NoRedirectSimpleClientHttpRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod)
            throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
