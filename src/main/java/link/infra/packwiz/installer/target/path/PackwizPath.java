package link.infra.packwiz.installer.target.path;

import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.target.ClientHolder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Abstract path representation for packwiz files.
 * Can represent either a local file path or an HTTP URL.
 */
public abstract class PackwizPath<T extends PackwizPath<T>> {
    protected final String path;

    protected PackwizPath(String path) {
        if (path != null) {
            if (path.contains("\0")) throw new RequestException.Validation.PathContainsNUL(path);
            String normalized = path.replace('\\', '/');
            String[] split = normalized.split("/");
            List<String> canonicalized = new ArrayList<>();

            int parentCount = 0;
            boolean first = true;
            for (int i = split.length - 1; i >= 0; i--) {
                String component = split[i];
                if (first) {
                    first = false;
                    if (component.isEmpty()) canonicalized.add(component);
                }
                String compNorm = component.replace("%2e", ".");
                if (compNorm.equals(".") || compNorm.isEmpty()) {
                    // skip
                } else if (compNorm.equals("..")) {
                    parentCount++;
                } else if (parentCount > 0) {
                    parentCount--;
                } else {
                    canonicalized.add(compNorm);
                    if (compNorm.length() == 2 && compNorm.charAt(1) == ':') {
                        char c = compNorm.charAt(0);
                        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                            throw new RequestException.Validation.PathContainsVolumeLetter(path);
                        }
                    }
                }
            }

            if (canonicalized.isEmpty()) {
                this.path = null;
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = canonicalized.size() - 1; i >= 0; i--) {
                    if (i < canonicalized.size() - 1) sb.append('/');
                    sb.append(canonicalized.get(i));
                }
                this.path = sb.toString();
            }
        } else {
            this.path = null;
        }
    }

    protected PackwizPath(String path, boolean skipValidation) {
        this.path = path; // direct assignment, no validation
    }

    protected abstract T construct(String path);

    protected Boolean pathFolder() {
        return path != null ? path.endsWith("/") : null;
    }

    protected String pathFilename() {
        if (path == null) return null;
        String[] parts = path.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    public abstract boolean folder();
    public abstract String filename();

    public T resolve(String path) {
        if (path.startsWith("/") || path.startsWith("\\")) {
            return construct(path);
        } else if (folder()) {
            return construct((this.path != null ? this.path : "") + path);
        } else {
            return construct((this.path != null ? this.path : "") + "/../" + path);
        }
    }

    @SuppressWarnings("unchecked")
    public <U extends PackwizPath<U>> U rebase(U base) {
        return base.resolve(this.path != null ? this.path : "");
    }

    public T parent() {
        return resolve(folder() ? ".." : ".");
    }

    /**
     * Obtain an InputStream for this path.
     */
    public abstract InputStream source(ClientHolder clientHolder) throws RequestException;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackwizPath<?> that = (PackwizPath<?>) o;
        return Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(path);
    }

    @Override
    public String toString() {
        return "(Unknown base) " + path;
    }
}
