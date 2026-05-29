package link.infra.packwiz.installer.target.path;

import link.infra.packwiz.installer.request.RequestException;
import link.infra.packwiz.installer.target.ClientHolder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A PackwizPath backed by a local filesystem path.
 */
public class PackwizFilePath extends PackwizPath<PackwizFilePath> {
    private final Path base;

    public PackwizFilePath(Path base) {
        this(base, null);
    }

    public PackwizFilePath(Path base, String path) {
        super(path);
        this.base = base;
    }

    @Override
    protected PackwizFilePath construct(String path) {
        return new PackwizFilePath(base, path);
    }

    @Override
    public InputStream source(ClientHolder clientHolder) throws RequestException {
        Path resolved = nioPath();
        try {
            return Files.newInputStream(resolved);
        } catch (FileNotFoundException e) {
            throw new RequestException.Response.File.FileNotFound(resolved.toString());
        } catch (IOException e) {
            throw new RequestException.Response.File.Other(e);
        }
    }

    public Path nioPath() {
        if (path == null) return base;
        return base.resolve(path);
    }

    @Override
    public boolean folder() {
        Boolean pf = pathFolder();
        if (pf != null) return pf;
        String last = base.getFileName().toString();
        return last.isEmpty();
    }

    @Override
    public String filename() {
        String pf = pathFilename();
        if (pf != null) return pf;
        return base.getFileName().toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackwizFilePath other)) return false;
        if (!super.equals(o)) return false;
        return base.equals(other.base);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + base.hashCode();
    }

    @Override
    public String toString() {
        return nioPath().toString();
    }
}
