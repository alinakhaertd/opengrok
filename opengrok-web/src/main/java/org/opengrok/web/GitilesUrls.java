/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.web;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jetbrains.annotations.Nullable;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.history.HistoryGuru;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.web.Util;

/**
 * Builds Gitiles URLs for files displayed by the web application.
 * <p>
 * The target repository is resolved from OpenGrok metadata, while the browse base URL is taken from the
 * Git remote configuration of the working copy. Reading the remote directly keeps the links correct for
 * checkouts managed by {@code repo}, where remotes carry custom names and the filesystem layout does not
 * necessarily mirror the remote path.
 */
final class GitilesUrls {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitilesUrls.class);

    private static final String REMOTE_SECTION = "remote";

    private static final String GIT_SUFFIX = ".git";

    /** Browse base URLs are stable for the lifetime of the webapp, so they are resolved once per repository. */
    private static final ConcurrentMap<String, Optional<String>> BROWSE_BASE_CACHE = new ConcurrentHashMap<>();

    private GitilesUrls() {
    }

    /**
     * @param path source root relative path of the displayed resource
     * @param resourceFile displayed resource, may be {@code null}
     * @param project project the resource belongs to, may be {@code null}
     * @return Gitiles URL or {@code null} when it cannot be determined
     */
    @Nullable
    static String getUrl(@Nullable String path, @Nullable File resourceFile, @Nullable Project project) {
        String pagePath = withLeadingSlash(path == null || path.isBlank() ? "" : path);

        RepositoryInfo repository = resolveRepository(pagePath, resourceFile, project);
        if (repository == null) {
            return null;
        }

        String repositoryRoot = withLeadingSlash(repository.getDirectoryNameRelative());
        String browseBase = getBrowseBase(repositoryRoot);
        if (browseBase == null) {
            return null;
        }

        String revision = repository.getBranch();
        if (revision == null || revision.isBlank()) {
            revision = Constants.HEAD;
        }

        StringBuilder url = new StringBuilder(browseBase).append("/+/").append(Util.uriEncode(revision));
        String relativePath = pagePath.startsWith(repositoryRoot) ? pagePath.substring(repositoryRoot.length()) : "";
        for (String segment : relativePath.split("/")) {
            if (!segment.isEmpty()) {
                url.append('/').append(Util.uriEncode(segment));
            }
        }
        return url.toString();
    }

    @Nullable
    private static RepositoryInfo resolveRepository(String pagePath, @Nullable File resourceFile,
                                                    @Nullable Project project) {
        if (resourceFile != null && resourceFile.exists()) {
            RepositoryInfo direct = HistoryGuru.getInstance().getRepository(resourceFile);
            if (direct != null && hasRoot(direct)) {
                return direct;
            }
        }

        String projectPath = project != null ? withLeadingSlash(project.getPath()) : null;
        RepositoryInfo best = null;
        int bestLength = -1;
        for (RepositoryInfo candidate : RuntimeEnvironment.getInstance().getRepositories()) {
            if (candidate == null || !hasRoot(candidate)) {
                continue;
            }
            String candidateRoot = withLeadingSlash(candidate.getDirectoryNameRelative());
            boolean matches = contains(candidateRoot, pagePath) || contains(candidateRoot, projectPath);
            // Prefer the most deeply nested repository containing the path.
            if (matches && candidateRoot.length() > bestLength) {
                best = candidate;
                bestLength = candidateRoot.length();
            }
        }
        return best;
    }

    private static boolean hasRoot(RepositoryInfo repository) {
        return repository.getDirectoryNameRelative() != null && !repository.getDirectoryNameRelative().isBlank();
    }

    private static boolean contains(String root, @Nullable String path) {
        return path != null && (path.equals(root) || path.startsWith(root + "/"));
    }

    private static String withLeadingSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    @Nullable
    private static String getBrowseBase(String repositoryRoot) {
        return BROWSE_BASE_CACHE.computeIfAbsent(repositoryRoot, root -> {
            File repositoryDir = new File(RuntimeEnvironment.getInstance().getSourceRootPath(), root.substring(1));
            return Optional.ofNullable(readRemoteUrl(repositoryDir)).map(GitilesUrls::toBrowseBase);
        }).orElse(null);
    }

    /**
     * Read the remote URL of a repository, preferring {@code origin} and falling back to the first remote
     * in alphabetical order.
     */
    @Nullable
    private static String readRemoteUrl(File repositoryDir) {
        if (!repositoryDir.isDirectory()) {
            return null;
        }
        try (org.eclipse.jgit.lib.Repository gitRepository = new FileRepositoryBuilder()
                .findGitDir(repositoryDir)
                .setMustExist(true)
                .build()) {
            Set<String> remotes = gitRepository.getConfig().getSubsections(REMOTE_SECTION);
            if (remotes.isEmpty()) {
                return null;
            }
            String remote = remotes.contains(Constants.DEFAULT_REMOTE_NAME)
                    ? Constants.DEFAULT_REMOTE_NAME
                    : new TreeSet<>(remotes).first();
            return gitRepository.getConfig().getString(REMOTE_SECTION, remote, "url");
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, e, () -> String.format("Cannot determine Git remote for '%s'", repositoryDir));
            return null;
        }
    }

/**
 * Normalize a Git remote URL into a browsable HTTP(S) base URL.
 */
    @Nullable
    private static String toBrowseBase(String remoteUrl) {
        URI remoteUri;
        try {
            remoteUri = URI.create(remoteUrl.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }

        String scheme = remoteUri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))) {
            return null;
        }

        String host = remoteUri.getHost();
        if (host == null || remoteUri.getRawPath() == null) {
            return null;
        }

        String path = remoteUri.getRawPath();
        if (path.endsWith(GIT_SUFFIX)) {
            path = path.substring(0, path.length() - GIT_SUFFIX.length());
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return null;
        }

        String authority = remoteUri.getRawAuthority();
        // Drop any credentials that may be present in the remote URL.
        String sanitizedAuthority = authority.substring(authority.indexOf('@') + 1);

        StringBuilder browseBase = new StringBuilder()
                .append(scheme.toLowerCase(Locale.ROOT))
                .append("://")
                .append(sanitizedAuthority);

        // Gerrit exposes repositories through the Gitiles plugin context.
        if (host.toLowerCase(Locale.ROOT).contains("gerrit")
                && !path.startsWith("/plugins/gitiles/")) {
            browseBase.append("/plugins/gitiles");
        }

        return browseBase.append(path).toString();
    }
}
