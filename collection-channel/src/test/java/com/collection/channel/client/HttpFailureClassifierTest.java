package com.collection.channel.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class HttpFailureClassifierTest {

    @Test
    void rateLimitIsProvablyNotSent() {
        assertTrue(
                HttpFailureClassifier.provablyNotSent(
                        new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)));
    }

    @Test
    void serverErrorIsNotProvable() {
        assertFalse(
                HttpFailureClassifier.provablyNotSent(
                        new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)));
    }

    @Test
    void connectPhaseFailuresAreProvablyNotSent() {
        assertTrue(
                HttpFailureClassifier.provablyNotSent(
                        new ResourceAccessException("refused", new ConnectException("refused"))));
        assertTrue(
                HttpFailureClassifier.provablyNotSent(
                        new ResourceAccessException("dns", new UnknownHostException("host"))));
        assertTrue(
                HttpFailureClassifier.provablyNotSent(
                        new ResourceAccessException(
                                "tls", new SSLHandshakeException("handshake"))));
    }

    @Test
    void socketTimeoutIsNotProvable() {
        // SimpleClientHttpRequestFactory 下连接超时与读超时同为 SocketTimeoutException，无法区分
        assertFalse(
                HttpFailureClassifier.provablyNotSent(
                        new ResourceAccessException(
                                "timeout", new SocketTimeoutException("read timed out"))));
    }

    @Test
    void unrelatedExceptionIsNotProvable() {
        assertFalse(HttpFailureClassifier.provablyNotSent(new IllegalStateException("boom")));
    }
}
