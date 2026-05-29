package link.infra.packwiz.installer.target.path;

import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.target.ClientHolder;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;

/**
 * A PackwizPath backed by an HTTP(S) URL.
 */
public class HttpUrlPath extends PackwizPath<HttpUrlPath> {
    private final URI url;

    public HttpUrlPath(URI url) {
        this(url, null);
    }

    public HttpUrlPath(URI url, String path) {
        super(path);
        this.url = url;
    }

    private URI build() {
        if (path == null) return url;
        URI base = url.getPath().endsWith("/") ? url : URI.create(url + "/");
        return base.resolve(path);
    }

    @Override
    public InputStream source(ClientHolder clientHolder) throws RequestException {
        URI uri = build();
        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "packwiz-installer")
            .GET()
            .build();
        var result = clientHolder.httpGet(req);
        if (result.code() < 200 || result.code() >= 300) {
            try { result.body().close(); } catch (java.io.IOException ignored) {}
            throw new RequestException.Response.HTTP.ErrorCode(uri, result.code());
        }
        return result.body();
    }

    @Override
    protected HttpUrlPath construct(String path) {
        return new HttpUrlPath(url, path);
    }

    @Override
    public boolean folder() {
        Boolean pf = pathFolder();
        if (pf != null) return pf;
        String urlPath = url.getPath();
        return urlPath.endsWith("/") || urlPath.isEmpty();
    }

    @Override
    public String filename() {
        String pf = pathFilename();
        if (pf != null) return pf;
        String[] segments = url.getPath().split("/");
        return segments.length > 0 ? segments[segments.length - 1] : "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HttpUrlPath other)) return false;
        if (!super.equals(o)) return false;
        return url.equals(other.url);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + url.hashCode();
    }

    @Override
    public String toString() {
        return build().toString();
    }
}
