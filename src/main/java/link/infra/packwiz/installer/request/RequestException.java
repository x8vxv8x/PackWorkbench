package link.infra.packwiz.installer.request;

import java.io.IOException;
import java.net.URI;

public sealed class RequestException extends RuntimeException {

    private RequestException(String message, Throwable cause) { super(message, cause); }
    private RequestException(String message) { super(message); }

    // ===== Internal errors (should not be shown to user) =====

    public static sealed class Internal extends RequestException {
        private Internal(String message, Throwable cause) { super(message, cause); }
        private Internal(String message) { super(message); }

        public static sealed class HTTP extends Internal {
            private HTTP(String message, Throwable cause) { super(message, cause); }
            private HTTP(String message) { super(message); }

            public static final class NoResponseBody extends HTTP {
                public NoResponseBody() { super("HTTP 响应中必须包含响应体"); }
            }
            public static final class RequestFailed extends HTTP {
                public RequestFailed(IOException cause) { super("HTTP 请求失败", cause); }
            }
            public static final class IllegalState extends HTTP {
                public IllegalState(IllegalStateException cause) { super("内部致命 HTTP 请求错误", cause); }
            }
        }
    }

    // ===== Validation errors (malformed request) =====

    public static sealed class Validation extends RequestException {
        private Validation(String message) { super(message); }

        public static final class PathContainsNUL extends Validation {
            public PathContainsNUL(String path) { super("Invalid path; contains NUL bytes: " + path.replace("\0", "")); }
        }
        public static final class PathContainsVolumeLetter extends Validation {
            public PathContainsVolumeLetter(String path) { super("路径无效；包含卷标字母：" + path); }
        }
    }

    // ===== Response errors =====

    public static sealed class Response extends RequestException {
        private Response(String message, Throwable cause) { super(message, cause); }
        private Response(String message) { super(message); }

        public static sealed class HTTP extends Response {
            private final URI uri;
            private final int code;

            private HTTP(URI uri, int code, String message, Throwable cause) {
                super("向 " + uri + " 发送 HTTP 请求失败：" + message, cause);
                this.uri = uri;
                this.code = code;
            }
            private HTTP(URI uri, int code, String message) {
                super("向 " + uri + " 发送 HTTP 请求失败：" + message);
                this.uri = uri;
                this.code = code;
            }

            public URI uri() { return uri; }
            public int code() { return code; }

            public static final class ErrorCode extends HTTP {
                public ErrorCode(URI uri, int code) { super(uri, code, "HTTP 请求返回非成功错误代码：" + code); }
            }
        }

        public static sealed class File extends Response {
            private File(String message, Throwable cause) { super(message, cause); }
            private File(String message) { super(message); }

            public static final class FileNotFound extends File {
                public FileNotFound(String file) { super("文件路径未找到：" + file); }
            }
            public static final class Other extends File {
                public Other(Throwable cause) { super("读取文件失败", cause); }
            }
        }
    }
}
