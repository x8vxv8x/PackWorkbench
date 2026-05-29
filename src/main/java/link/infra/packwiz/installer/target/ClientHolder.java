package link.infra.packwiz.installer.target;

import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

public class ClientHolder {
    private static final Duration[] RETRY_TIMEOUTS = {
        Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(60)
    };

    private final HttpClient httpClient;

    public ClientHolder() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(RETRY_TIMEOUTS[0])
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public record HttpResult(int code, InputStream body) {}

    private HttpRequest copyWithTimeout(HttpRequest original, Duration timeout) {
        var builder = HttpRequest.newBuilder(original.uri()).timeout(timeout);
        original.headers().map().forEach((k, v) -> builder.header(k, String.join(",", v)));
        original.bodyPublisher().ifPresent(p -> builder.method(original.method(), p));
        return builder.build();
    }

    public HttpResult httpGet(HttpRequest request) throws RequestException {
        HttpTimeoutException lastException = null;

        for (int tryCount = 0; tryCount <= RETRY_TIMEOUTS.length; tryCount++) {
            Duration timeout = tryCount == 0 ? RETRY_TIMEOUTS[0] : RETRY_TIMEOUTS[tryCount - 1];
            HttpRequest req = copyWithTimeout(request, timeout);

            try {
                HttpResponse<InputStream> res = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                InputStream body = res.body();
                if (body == null) throw new RequestException.Internal.HTTP.NoResponseBody();
                return new HttpResult(res.statusCode(), body);
            } catch (HttpTimeoutException e) {
                lastException = e;
                Log.info("HTTP connection to " + request.uri() + " timed out; retrying... (" + tryCount + "/" + RETRY_TIMEOUTS.length + ")");
            } catch (IOException e) {
                throw new RequestException.Internal.HTTP.RequestFailed(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RequestException.Internal.HTTP.RequestFailed(new IOException(e));
            }
        }

        throw new RequestException.Internal.HTTP.RequestFailed(lastException);
    }

    public HttpResponse<InputStream> httpRequest(HttpRequest request) throws RequestException {
        HttpTimeoutException lastException = null;

        for (int tryCount = 0; tryCount <= RETRY_TIMEOUTS.length; tryCount++) {
            Duration timeout = tryCount == 0 ? RETRY_TIMEOUTS[0] : RETRY_TIMEOUTS[tryCount - 1];
            HttpRequest req = copyWithTimeout(request, timeout);

            try {
                return httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                lastException = e;
                Log.info("HTTP connection to " + request.uri() + " timed out; retrying... (" + tryCount + "/" + RETRY_TIMEOUTS.length + ")");
            } catch (IOException e) {
                throw new RequestException.Internal.HTTP.RequestFailed(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RequestException.Internal.HTTP.RequestFailed(new IOException(e));
            }
        }

        throw new RequestException.Internal.HTTP.RequestFailed(lastException);
    }

    public void close() {
        // HttpClient is lightweight, GC handles cleanup
    }
}
