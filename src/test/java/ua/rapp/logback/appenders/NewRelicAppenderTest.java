package ua.rapp.logback.appenders;

import ch.qos.logback.core.Context;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class NewRelicAppenderTest {

    private static final String EVENT = "{\"@timestamp\": \"2023-04-18T15:46:36.969577+03:00\"," +
            "\"@version\": \"1\",\"level\": \"DEBUG\",\"logger\": \"ua.rapp.Main\"," +
            "\"message\": \"Received a new message from userId={*********}.\"," +
            "\"newrelic.source\": \"api.logs\",\"thread\": \"main\",\"timestamp\": 1681821996969," +
            "\"uuid\": \"95c3f63d-0ff3-43ff-83f6-3d0ff393ff5c\"}";

    private NewRelicAppender newRelicAppender;

    @BeforeEach
    void setup() {
        newRelicAppender = spy(new NewRelicAppender());

        var statusManager = mock(StatusManager.class);
        doNothing().when(statusManager).add(any(Status.class));
        var context = mock(Context.class);
        when(context.getStatusManager()).thenReturn(statusManager);
        newRelicAppender.setContext(context);

    }

    @ParameterizedTest
    @MethodSource("appender_initialization_arguments")
    void test_appender_initialization(Layout layout, String host, String url, String apiKey, int count) {
        newRelicAppender.setLayout(layout);
        newRelicAppender.setHost(host);
        newRelicAppender.setUrl(url);
        newRelicAppender.setApiKey(apiKey);

        newRelicAppender.start();

        verify(newRelicAppender, times(count)).addError(Mockito.anyString());
    }

    static List<Object[]> appender_initialization_arguments() {
        return List.of(
                new Object[]{null, null, null, null, 4},
                new Object[]{mock(Layout.class), null, null, null, 3},
                new Object[]{mock(Layout.class), "hostname", null, null, 2},
                new Object[]{mock(Layout.class), "hostname", "url", null, 1},
                new Object[]{mock(Layout.class), "hostname", "url", "api-key", 0}
        );
    }

    @Test
    void test_compressed() throws IOException {
        newRelicAppender.setMaxUncompressedSize(256);
        var eventContainer = newRelicAppender.compressIfNeeded(EVENT);
        var bais = new ByteArrayInputStream(eventContainer.payload());
        var gzip = new GZIPInputStream(bais);
        assertArrayEquals(EVENT.getBytes(StandardCharsets.UTF_8), gzip.readAllBytes());
        assertTrue(eventContainer.compressed());
    }

    @Test
    void test_not_compressed() throws IOException {
        var eventContainer = newRelicAppender.compressIfNeeded(EVENT);
        assertArrayEquals(EVENT.getBytes(StandardCharsets.UTF_8), eventContainer.payload());
        assertFalse(eventContainer.compressed());
    }
}
