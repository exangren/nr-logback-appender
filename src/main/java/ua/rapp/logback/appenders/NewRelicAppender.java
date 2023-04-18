package ua.rapp.logback.appenders;


import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Layout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

public class NewRelicAppender extends AppenderBase<ILoggingEvent> {
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String CONTENT_TYPE = "application/json";
    private static final String CONTENT_ENCODING_HEADER = "Content-Encoding";
    private static final String CONTENT_ENCODING = "gzip";
    private static final String API_KEY_HEADER = "Api-Key";
    private static final int MIN_UNCOMPRESSED_SIZE = 256;
    private static final int MAX_UNCOMPRESSED_SIZE = 1024;
    private static final int MAX_PAYLOAD_SIZE = MAX_UNCOMPRESSED_SIZE * 1000;
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(2);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final int DEFAULT_RETRY_NUMBER = 3;
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(EXECUTOR_SERVICE)
            .connectTimeout(DEFAULT_TIMEOUT)
            .build();

    private Layout<ILoggingEvent> layout;
    private String host;
    private String url;
    private String apiKey;
    private long retryInterval = -1;
    private int retryNumber = -1;
    private int maxPayloadSize = -1;
    private int maxUncompressedSize = -1;

    @Override
    public void start() {
        if (!validateSettings()) {
            return;
        }
        super.start();
    }

    @Override
    protected void append(ILoggingEvent loggingEvent) {
        var event = layout.doLayout(loggingEvent);

        if (event.length() > getMaxPayloadSize()) {
            addError("Total log message size should be less than [" + getMaxPayloadSize() + "] bytes.",
                    new IllegalArgumentException("Payload exceeds [" + getMaxPayloadSize() + "] bytes."));
            addError("Message: " + event);
            return;
        }

        PayloadContainer payload;
        try {
            payload = compressIfNeeded(event);
        } catch (IOException e) {
            addError("Could not compress message.", e);
            return;
        }
        sendPayload(payload);
    }

    private void sendPayload(PayloadContainer payload) {
        var httpRequest = createHttpRequest(payload);
        var responseHandler = HttpResponse.BodyHandlers.ofString();
        var response = httpClient.sendAsync(httpRequest, responseHandler)
                .thenComposeAsync(resp -> tryResend(httpRequest, responseHandler, 1, resp),
                        EXECUTOR_SERVICE);

        var result = handleResponse(response);
        try {
            addInfo(result.get(500, TimeUnit.MILLISECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            addError("Could not post message.", e);
        }
    }

    private CompletableFuture<String> handleResponse(CompletableFuture<HttpResponse<String>> response) {
        return response.handleAsync((result, exception) -> {
            if (exception != null) {
                return "An exception happened while logging: " + exception.getMessage();
            } else {
                return "Successfully logged: " + result.body();
            }
        });
    }

    private <T> CompletableFuture<HttpResponse<T>> tryResend(HttpRequest httpRequest,
                                                             HttpResponse.BodyHandler<T> handler,
                                                             int count,
                                                             HttpResponse<T> httpResponse) {

        // NR may confirm by 202 or 200. Otherwise, stop retrying.
        if (httpResponse.statusCode() < 300 || count >= getRetryNumber()) {
            return CompletableFuture.completedFuture(httpResponse);
        } else {
            try {
                Thread.sleep(getRetryInterval());
            } catch (InterruptedException e) {
                addError("While retrying a sleep is interrupted", e);
            }
            return httpClient.sendAsync(httpRequest, handler)
                    .thenComposeAsync(response -> tryResend(httpRequest, handler, count + 1, response),
                            EXECUTOR_SERVICE);
        }
    }

    private HttpRequest createHttpRequest(PayloadContainer payload) {
        var httpBuilder = HttpRequest.newBuilder()
                .header(CONTENT_TYPE_HEADER, CONTENT_TYPE)
                .header(ACCEPT_HEADER, CONTENT_TYPE)
                .header(API_KEY_HEADER, apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.payload))
                .uri(URI.create(host + url));
        if (payload.compressed) {
            httpBuilder.header(CONTENT_ENCODING_HEADER, CONTENT_ENCODING);
        }
        return httpBuilder.build();
    }

    PayloadContainer compressIfNeeded(String rawPayload) throws IOException {
        var payloadBytes = rawPayload.getBytes(StandardCharsets.UTF_8);
        var toBeCompressed = rawPayload.length() > getMaxUncompressedSize();
        var payload = toBeCompressed ? compress(payloadBytes) : payloadBytes;
        return new PayloadContainer(payload, toBeCompressed);
    }

    private byte[] compress(byte[] rawPayload) throws IOException {
        var outputByteStream = new ByteArrayOutputStream();
        try (var gzippedOutputStream = new GZIPOutputStream(outputByteStream)) {
            gzippedOutputStream.write(rawPayload);
        }
        return outputByteStream.toByteArray();
    }

    public Layout<ILoggingEvent> getLayout() {
        return layout;
    }

    public void setLayout(Layout<ILoggingEvent> layout) {
        this.layout = layout;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public long getRetryInterval() {
        return retryInterval >= 0 ? retryInterval : DEFAULT_RETRY_INTERVAL.toMillis();
    }

    public void setRetryInterval(long retryInterval) {
        this.retryInterval = retryInterval;
    }

    public int getRetryNumber() {
        return retryNumber >= 0 ? retryNumber : DEFAULT_RETRY_NUMBER;
    }

    public void setRetryNumber(int retryNumber) {
        this.retryNumber = retryNumber;
    }

    public int getMaxPayloadSize() {
        return maxPayloadSize > 0 ? maxPayloadSize : MAX_PAYLOAD_SIZE;
    }

    public void setMaxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = Math.min(maxPayloadSize, MAX_PAYLOAD_SIZE);
    }

    public int getMaxUncompressedSize() {
        return maxUncompressedSize > 0 ? maxUncompressedSize : MAX_UNCOMPRESSED_SIZE;
    }

    public void setMaxUncompressedSize(int maxUncompressedSize) {
        this.maxUncompressedSize = Math.max(maxUncompressedSize, MIN_UNCOMPRESSED_SIZE);
    }

    private boolean validateSettings() {
        if (layout == null) {
            addError(buildErrorValidationMessage("layout"));
        }
        if (host == null) {
            addError(buildErrorValidationMessage("host"));
        }
        if (url == null) {
            addError(buildErrorValidationMessage("url"));
        }
        if (apiKey == null) {
            addError(buildErrorValidationMessage("apiKey"));
        }
        return layout != null && host != null && url != null && apiKey != null;
    }

    private String buildErrorValidationMessage(String attribute) {
        return String.format("'%s' attribute is not set for the appender named [%s]!", attribute, name);
    }

    record PayloadContainer(byte[] payload, boolean compressed) {

    }
}
