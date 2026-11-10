package com.szsn.srm.monitor;

import com.szsn.srm.common.ApiBody;
import com.szsn.srm.common.ApiException;
import com.szsn.srm.common.Bodies;
import com.szsn.srm.history.HistoryService;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {
    private final SnapshotStore store;
    private final SseHub hub;
    private final HistoryService history;
    private final java.time.Clock clock;

    public MonitorController(SnapshotStore store, SseHub hub, HistoryService history, java.time.Clock clock) {
        this.store = store;
        this.hub = hub;
        this.history = history;
        this.clock = clock;
    }

    @GetMapping("/current")
    public ApiBody<MetricSnapshot> current() {
        MetricSnapshot latest = store.latest();
        if (latest == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COLLECTOR_STARTING", "还没有采集快照");
        }
        return Bodies.ok(latest.freshen(clock.millis()));
    }

    @GetMapping("/history")
    public ApiBody<HistoryService.HistoryView> history(@RequestParam long from, @RequestParam long to,
                                                       @RequestParam(required = false) Long bucketSeconds) {
        return Bodies.ok(history.query(from, to, bucketSeconds));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(jakarta.servlet.http.HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未登录");
        }
        Object lastActive = session.getAttribute(com.szsn.srm.auth.SessionActivityFilter.LAST_ACTIVE);
        if (lastActive instanceof Long last && clock.millis() - last > com.szsn.srm.auth.SessionActivityFilter.IDLE.toMillis()) {
            session.invalidate();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "登录已过期");
        }
        SseEmitter emitter = new SseEmitter(0L);
        SseHub.Client client = hub.connect(session.getId(), new EmitterSink(emitter), () -> alive(session));
        if (client == null) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SSE_LIMIT", "实时连接已达上限 5");
        }
        emitter.onCompletion(client::close);
        emitter.onTimeout(client::close);
        emitter.onError(error -> client.close());
        return emitter;
    }

    private boolean alive(HttpSession session) {
        try {
            Object value = session.getAttribute(com.szsn.srm.auth.SessionActivityFilter.LAST_ACTIVE);
            return value instanceof Long last && clock.millis() - last <= com.szsn.srm.auth.SessionActivityFilter.IDLE.toMillis();
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static final class EmitterSink implements SseHub.SseSink {
        private final SseEmitter emitter;

        private EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String event, String id, Object data) throws IOException {
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON);
            if (id != null) {
                builder.id(id);
            }
            emitter.send(builder);
        }

        @Override
        public void close() {
            emitter.complete();
        }
    }
}
