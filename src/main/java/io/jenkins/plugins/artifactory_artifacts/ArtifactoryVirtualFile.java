package io.jenkins.plugins.artifactory_artifacts;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.util.VirtualFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArtifactoryVirtualFile extends ArtifactoryAbstractVirtualFile {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactoryVirtualFile.class);

    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final String key;

    private final transient Run<?, ?> build;

    public ArtifactoryVirtualFile(String key, Run<?, ?> build) {
        this.key = key;
        this.build = build;
    }

    public String getKey() {
        return key;
    }

    @NonNull
    @Override
    public String getName() {
        String localKey = Utils.stripTrailingSlash(key);
        return localKey.replaceFirst(".*/artifacts/", "");
    }

    @NonNull
    @Override
    public URI toURI() {
        try {
            return new URI(Utils.getUrl(this.key));
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @CheckForNull
    @Override
    public URL toExternalURL() throws IOException {
        return new URL(Utils.getUrl(this.key));
    }

    @Override
    public VirtualFile getParent() {
        return new ArtifactoryVirtualFile(this.key.replaceFirst("/[^/]+$", ""), this.build);
    }

    @Override
    public boolean isDirectory() throws IOException {
        String keyWithNoSlash = Utils.stripTrailingSlash(this.key);
        if (keyWithNoSlash.endsWith("/*view*")) {
            return false;
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.isFolder(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to check if %s is a directory", this.key), e);
            return false;
        }
    }

    @Override
    public boolean isFile() throws IOException {
        String keyS = this.key + "/";
        if (keyS.endsWith("/*view*/")) {
            return false;
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.isFile(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to check if %s is a file", this.key), e);
            return false;
        }
    }

    @Override
    public boolean exists() throws IOException {
        return isDirectory() || isFile();
    }

    @NonNull
    @Override
    public VirtualFile[] list() throws IOException {
        String prefix = Utils.stripTrailingSlash(this.key) + "/";
        List<VirtualFile> files = listFilesFromPrefix(prefix);
        if (files.isEmpty()) {
            return new VirtualFile[0];
        }
        return files.toArray(new VirtualFile[0]);
    }

    @NonNull
    @Override
    public VirtualFile child(@NonNull String name) {
        String joinedKey = Utils.stripTrailingSlash(this.key) + "/" + name;
        return new ArtifactoryVirtualFile(joinedKey, build);
    }

    @Override
    public long length() throws IOException {
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.size(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to get size of %s", this.key), e);
            return 0;
        }
    }

    @Override
    public long lastModified() throws IOException {
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.lastUpdated(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to get last updated time of %s", this.key), e);
            return 0;
        }
    }

    @Override
    public boolean canRead() throws IOException {
        return true;
    }

    @Override
    public InputStream open() throws IOException {
        LOGGER.debug(String.format("Opening %s...", this.key));
        if (isDirectory()) {
            throw new FileNotFoundException("Cannot open it because it is a directory.");
        }
        if (!isFile()) {
            throw new FileNotFoundException("Cannot open it because it is not a file.");
        }
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            return client.downloadArtifact(this.key);
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to open %s", this.key), e);
            throw new IOException(e);
        }
    }

    private ArtifactoryClient buildArtifactoryClient() {
        ArtifactoryGenericArtifactConfig config = Utils.getArtifactConfig();
        return new ArtifactoryClient(config.getServerUrl(), config.getRepository(), Utils.getCredentials());
    }

    /**
     * List the files from a prefix
     * @param prefix the prefix
     * @return the list of files from the prefix
     */
    private List<VirtualFile> listFilesFromPrefix(String prefix) {
        try (ArtifactoryClient client = buildArtifactoryClient()) {
            List<String> files = client.list(prefix);
            List<VirtualFile> virtualFiles = new ArrayList<>();
            for (String file : files) {
                virtualFiles.add(new ArtifactoryVirtualFile(Utils.stripTrailingSlash(file), this.build));
            }
            return virtualFiles;
        } catch (Exception e) {
            LOGGER.warn(String.format("Failed to list files from prefix %s", prefix), e);
            return Collections.emptyList();
        }
    }
}
