package com.pluginfence.agent.subject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** Process, network and environment operations performed by the controlled subject code. */
public final class SystemSubject {

    private SystemSubject() {
    }

    // --- processes

    public static Process start(List<String> command) throws IOException {
        return new ProcessBuilder(command).start();
    }

    public static Process exec(String[] command) throws IOException {
        return Runtime.getRuntime().exec(command);
    }

    // --- network

    public static void connect(String host, int port, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        }
    }

    public static void connectViaConstructor(String host, int port) throws IOException {
        new Socket(host, port).close();
    }

    public static URLConnection open(String url) throws IOException {
        return new URL(url).openConnection();
    }

    public static int httpStatus(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(1000);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    public static int httpClientStatus(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    // --- environment

    public static String env(String name) {
        return System.getenv(name);
    }

    public static Map<String, String> allEnv() {
        return System.getenv();
    }
}
