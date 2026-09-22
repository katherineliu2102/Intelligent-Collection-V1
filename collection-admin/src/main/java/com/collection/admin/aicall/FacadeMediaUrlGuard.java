package com.collection.admin.aicall;

import java.net.URI;
import org.apache.commons.lang3.StringUtils;

/** script_url / recording_url 必须与 Facade baseUrl 同 scheme、host、端口，避免 SSRF。 */
public final class FacadeMediaUrlGuard {

    private FacadeMediaUrlGuard() {}

    public static boolean hostAllowed(String mediaUrl, String facadeBaseUrl) {
        if (StringUtils.isBlank(mediaUrl) || StringUtils.isBlank(facadeBaseUrl)) {
            return false;
        }
        try {
            URI media = URI.create(mediaUrl.trim());
            URI base = URI.create(facadeBaseUrl.trim());
            String mediaHost = media.getHost();
            String baseHost = base.getHost();
            if (mediaHost == null || baseHost == null) {
                return false;
            }
            if (media.getScheme() == null || base.getScheme() == null) {
                return false;
            }
            if (!media.getScheme().equalsIgnoreCase(base.getScheme())) {
                return false;
            }
            if (!mediaHost.equalsIgnoreCase(baseHost)) {
                return false;
            }
            return effectivePort(media) == effectivePort(base);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port > 0) {
            return port;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        return -1;
    }
}
