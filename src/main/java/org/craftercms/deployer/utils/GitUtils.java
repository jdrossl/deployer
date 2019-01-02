/*
 * Copyright (C) 2007-2018 Crafter Software Corporation. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.craftercms.deployer.utils;

import org.apache.commons.lang3.StringUtils;
import org.craftercms.commons.git.auth.GitAuthenticationConfigurator;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;

/**
 * Utility methods for Git operations.
 *
 * @author avasquez
 */
public abstract class GitUtils {

    public static final String GIT_FOLDER_NAME = ".git";

    public static final String CORE_CONFIG_SECTION = "core";
    public static final String BIG_FILE_THRESHOLD_CONFIG_PARAM = "bigFileThreshold";
    public static final String COMPRESSION_CONFIG_PARAM = "compression";
    public static final String FILE_MODE_CONFIG_PARAM = "fileMode";

    public static final String BIG_FILE_THRESHOLD_DEFAULT = "20m";
    public static final int COMPRESSION_DEFAULT = 0;
    public static final boolean FILE_MODE_DEFAULT = false;

    private GitUtils() {
    }

    /**
     * Opens the Git repository at the specified location.
     *
     * @param localRepositoryFolder the folder where the Git repository is
     * @return the Git instance used to handle the repository
     * @throws IOException if an error occurs
     */
    public static Git openRepository(File localRepositoryFolder) throws IOException {
        return Git.open(localRepositoryFolder);
    }

    /**
     * Clones a remote repository into a specific local folder.
     *
     * @param remoteName       the name of the remote
     * @param remoteUrl        the URL of the remote. This should be a legal Git URL.
     * @param branch           the branch which should be cloned
     * @param authConfigurator the {@link GitAuthenticationConfigurator} class used to configure the authentication
     *                         with the remote repository
     * @param localFolder      the local folder into which the remote repository should be cloned
     * @param bigFileThreshold the value of the Git {@code core.bigFileThreshold} config property
     * @param compression      the value of the Git {@code core.compression} config property
     * @param fileMode         the value of the Git {@code core.fileMode} config property
     * @return the Git instance used to handle the cloned repository
     * @throws GitAPIException if a Git related error occurs
     * @throws IOException     if an IO error occurs
     */
    public static Git cloneRemoteRepository(String remoteName, String remoteUrl, String branch,
                                            GitAuthenticationConfigurator authConfigurator, File localFolder,
                                            String bigFileThreshold, Integer compression,
                                            Boolean fileMode) throws GitAPIException, IOException {
        CloneCommand command = Git.cloneRepository();
        command.setRemote(remoteName);
        command.setURI(remoteUrl);
        command.setDirectory(localFolder);

        if (StringUtils.isNotEmpty(branch)) {
            command.setCloneAllBranches(false);
            command.setBranchesToClone(Collections.singletonList(Constants.R_HEADS + branch));
            command.setBranch(branch);
        }

        if (authConfigurator != null) {
            authConfigurator.configureAuthentication(command);
        }

        Git git = command.call();
        StoredConfig config = git.getRepository().getConfig();

        if (StringUtils.isEmpty(bigFileThreshold)) {
            bigFileThreshold = BIG_FILE_THRESHOLD_DEFAULT;
        }
        if (compression == null) {
            compression = COMPRESSION_DEFAULT;
        }
        if (fileMode == null) {
            fileMode = FILE_MODE_DEFAULT;
        }

        config.setString(CORE_CONFIG_SECTION, null, BIG_FILE_THRESHOLD_CONFIG_PARAM, bigFileThreshold);
        config.setInt(CORE_CONFIG_SECTION, null, COMPRESSION_CONFIG_PARAM, compression);
        config.setBoolean(CORE_CONFIG_SECTION, null, FILE_MODE_CONFIG_PARAM, fileMode);
        config.save();

        return git;
    }

    /**
     * Execute a Git pull.
     *
     * @param git              the Git instance used to handle the repository
     * @param remoteName       the name of the remote where to pull from
     * @param remoteUrl        the URL of the remote (remote will be set to the URL)
     * @param branch           the branch to pull
     * @param mergeStrategy    the merge strategy to use
     * @param authConfigurator the {@link GitAuthenticationConfigurator} class used to configure the authentication
     *                         with the remote repository
     * @return the result of the pull
     * @throws GitAPIException if a Git related error occurs
     * @throws URISyntaxException if the remote URL is invalid
     */
    public static PullResult pull(Git git, String remoteName, String remoteUrl, String branch,
                                  MergeStrategy mergeStrategy, GitAuthenticationConfigurator authConfigurator)
            throws GitAPIException, URISyntaxException {
        addRemote(git, remoteName, remoteUrl);

        PullCommand command = git.pull();
        command.setRemote(remoteName);
        command.setRemoteBranchName(branch);

        if (mergeStrategy != null) {
            command.setStrategy(mergeStrategy);
        }

        if (authConfigurator != null) {
            authConfigurator.configureAuthentication(command);
        }

        return command.call();
    }

    /**
     * Executes a git push.
     *
     * @param git              the Git instance used to handle the repository
     * @param remote           remote name or URL
     * @param branch           the remote branch being pushed to
     * @param authConfigurator the {@link GitAuthenticationConfigurator} class used to configure the authentication
     *                         with the remote
     *                         repository
     * @return the result of the push
     * @throws GitAPIException if a Git related error occurs
     */
    public static Iterable<PushResult> push(Git git, String remote, String branch,
                                            GitAuthenticationConfigurator authConfigurator) throws GitAPIException {
        PushCommand push = git.push();
        push.setRemote(remote);
        if (StringUtils.isNotEmpty(branch)) {
            push.setRefSpecs(new RefSpec(Constants.HEAD + ":" + Constants.R_HEADS + branch));
        }

        if (authConfigurator != null) {
            authConfigurator.configureAuthentication(push);
        }

        return push.call();
    }

    /**
     * Executes a git gc.
     * @param repoPath full path of the repository
     * @throws GitAPIException if there is an error running the command
     * @throws IOException if there is an error opening the repository
     */
    public static void cleanup(String repoPath) throws GitAPIException, IOException {
        openRepository(new File(repoPath)).gc().call();
    }

    /**
     * Adds a remote if it doesn't exist. If the remote exists but the URL is different, updates the URL
     *
     * @param git the Git repo
     * @param remoteName the name oif the remote
     * @param remoteUrl the URL of the remote
     *
     * @throws GitAPIException if a Git error occurs
     * @throws URISyntaxException if the remote URL is an invalid Git URL
     */
    private static void addRemote(Git git, String remoteName, String remoteUrl) throws GitAPIException,
                                                                                       URISyntaxException {
        String currentUrl = git.getRepository().getConfig().getString("remote", remoteName, "url");
        if (StringUtils.isNotEmpty(currentUrl)) {
            if (!currentUrl.equals(remoteUrl)) {
                RemoteSetUrlCommand remoteSetUrl = git.remoteSetUrl();
                remoteSetUrl.setName(remoteName);
                remoteSetUrl.setUri(new URIish(remoteUrl));
                remoteSetUrl.call();
            }
        } else {
            RemoteAddCommand remoteAdd = git.remoteAdd();
            remoteAdd.setName(remoteName);
            remoteAdd.setUri(new URIish(remoteUrl));
            remoteAdd.call();
        }
    }

}
