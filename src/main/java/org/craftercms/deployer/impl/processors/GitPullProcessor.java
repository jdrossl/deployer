/*
 * Copyright (C) 2007-2016 Crafter Software Corporation.
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
package org.craftercms.deployer.impl.processors;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.HiddenFileFilter;
import org.craftercms.deployer.api.ChangeSet;
import org.craftercms.deployer.api.Deployment;
import org.craftercms.deployer.api.ProcessorExecution;
import org.craftercms.deployer.api.exceptions.DeployerException;
import org.craftercms.deployer.utils.ConfigUtils;
import org.craftercms.deployer.utils.GitUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

/**
 * Created by alfonsovasquez on 1/12/16.
 */
public class GitPullProcessor extends AbstractMainDeploymentProcessor {

    public static final String REMOTE_REPO_URL_CONFIG_KEY = "remoteRepo.url";
    public static final String REMOTE_REPO_BRANCH_CONFIG_KEY = "remoteRepo.branch";
    public static final String REMOTE_REPO_USERNAME_CONFIG_KEY = "remoteRepo.username";
    public static final String REMOTE_REPO_PASSWORD_CONFIG_KEY = "remoteRepo.password";

    public static final String GIT_FOLDER_NAME = ".git";

    private static final Logger logger = LoggerFactory.getLogger(GitPullProcessor.class);

    protected File localRepoFolder;

    protected String remoteRepoUrl;
    protected String remoteRepoBranch;
    protected String remoteRepoUsername;
    protected String remoteRepoPassword;

    @Required
    public void setLocalRepoFolder(File localRepoFolder) {
        this.localRepoFolder = localRepoFolder;
    }

    @Override
    protected void doConfigure(Configuration config) throws DeployerException {
        remoteRepoUrl = ConfigUtils.getRequiredStringProperty(config, REMOTE_REPO_URL_CONFIG_KEY);
        remoteRepoBranch = ConfigUtils.getStringProperty(config, REMOTE_REPO_BRANCH_CONFIG_KEY);
        remoteRepoUsername = ConfigUtils.getStringProperty(config, REMOTE_REPO_USERNAME_CONFIG_KEY);
        remoteRepoPassword = ConfigUtils.getStringProperty(config, REMOTE_REPO_PASSWORD_CONFIG_KEY);
    }

    @Override
    protected boolean shouldExecute(Deployment deployment, ChangeSet filteredChangeSet) {
        // Run if the deployment is running
        return deployment.isRunning();
    }

    @Override
    protected ChangeSet doExecute(Deployment deployment, ProcessorExecution execution,
                                  ChangeSet filteredChangeSet) throws DeployerException {
        File gitFolder = new File(localRepoFolder, GIT_FOLDER_NAME);
        if (localRepoFolder.exists() && gitFolder.exists()) {
            return doPull(execution);
        } else {
            return doClone(execution);
        }
    }

    @Override
    protected boolean failDeploymentOnProcessorFailure() {
        return true;
    }

    protected ChangeSet doPull(ProcessorExecution execution) throws DeployerException {
        Git git = openLocalRepository();
        try {
            return pullChanges(git, execution);
        } finally {
            if (git != null) {
                git.close();
            }
        }
    }

    protected Git openLocalRepository() throws DeployerException {
        try {
            logger.info("Opening local Git repository at {}", localRepoFolder);

            return GitUtils.openRepository(localRepoFolder);
        } catch (IOException e) {
            throw new DeployerException("Failed to open Git repository at " + localRepoFolder, e);
        }
    }

    protected ChangeSet pullChanges(Git git, ProcessorExecution execution) throws DeployerException {
        try {
            logger.info("Executing git pull for repository {}...", localRepoFolder);

            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            PullResult pullResult = git.pull().call();

            if (pullResult.isSuccessful()) {
                MergeResult mergeResult = pullResult.getMergeResult();
                ChangeSet changeSet;
                String details;

                switch (mergeResult.getMergeStatus()) {
                    case FAST_FORWARD:
                        details = "Changes successfully pulled from remote " + remoteRepoUrl + " into local repository " + localRepoFolder;

                        logger.info(details);

                        changeSet = resolveChangeSetFromPull(git, head, mergeResult.getNewHead());

                        execution.setStatusDetails(details);

                        return changeSet;
                    case ALREADY_UP_TO_DATE:
                        details = "Local repository " + localRepoFolder + " up to date (no changes pulled from remote " + remoteRepoUrl +
                                  ")";

                        logger.info(details);

                        execution.setStatusDetails(details);

                        return null;
                    case MERGED:
                        details = "Changes from remote " + remoteRepoUrl + " merged into local repository " + localRepoFolder;

                        logger.info(details);

                        changeSet = resolveChangeSetFromPull(git, head, mergeResult.getNewHead());

                        execution.setStatusDetails(details);

                        return changeSet;
                    default:
                        // Non-supported merge results
                        throw new DeployerException("Received unsupported merge result after executing pull " + pullResult);
                }
            } else {
                throw new DeployerException("Git pull for repository " + localRepoFolder + " failed: " + pullResult);
            }
        } catch (IOException | GitAPIException e) {
            throw new DeployerException("Git pull for repository " + localRepoFolder + " failed", e);
        }
    }

    protected ChangeSet doClone(ProcessorExecution execution) throws DeployerException {
        Git git = cloneRemoteRepository();
        try {
            return resolveChangesFromClone(execution);
        } finally {
            git.close();
        }
    }

    protected Git cloneRemoteRepository() throws DeployerException {
        try {
            if (localRepoFolder.exists()) {
                logger.debug("Deleting existing folder {} before cloning", localRepoFolder);

                FileUtils.forceDelete(localRepoFolder);
            } else {
                logger.debug("Creating folder {} and any nonexistent parents before cloning", localRepoFolder);

                FileUtils.forceMkdir(localRepoFolder);
            }

            logger.info("Cloning Git remote repository {} into {}", remoteRepoUrl, localRepoFolder);

            return GitUtils.cloneRemoteRepository(remoteRepoUrl, remoteRepoBranch, remoteRepoUsername, remoteRepoPassword, localRepoFolder);
        } catch (IOException | GitAPIException e) {
            // Force delete so there's no invalid remains
            FileUtils.deleteQuietly(localRepoFolder);

            throw new DeployerException("Failed to clone Git remote repository " + remoteRepoUrl + " into " + localRepoFolder, e);
        }
    }

    protected ChangeSet resolveChangesFromClone(ProcessorExecution execution) {
        List<String> createdFiles = new ArrayList<>();

        addClonedFilesToChangeSet(localRepoFolder, "", createdFiles);

        ChangeSet changeSet = new ChangeSet(createdFiles, Collections.emptyList(), Collections.emptyList());

        execution.setStatusDetails("Successfully cloned Git remote repository " + remoteRepoUrl + " into " + localRepoFolder);

        return changeSet;
    }

    protected void addClonedFilesToChangeSet(File parent, String parentPath, List<String> createdFiles) {
        String[] filenames = parent.list(HiddenFileFilter.VISIBLE);
        if (filenames != null) {
            for (String filename : filenames) {
                File file = new File(parent, filename);
                String path = FilenameUtils.concat(parentPath, filename);

                if (file.isDirectory()) {
                    addClonedFilesToChangeSet(file, path, createdFiles);
                } else {
                    logger.debug("New file: {}", path);

                    createdFiles.add(path);
                }
            }
        }
    }

    protected ChangeSet resolveChangeSetFromPull(Git git, ObjectId oldHead, ObjectId newHead) throws IOException, GitAPIException {
        List<String> createdFiles = new ArrayList<>();
        List<String> updatedFiles = new ArrayList<>();
        List<String> deletedFiles = new ArrayList<>();
        RevWalk revWalk = new RevWalk(git.getRepository());
        ObjectId oldHeadTree = revWalk.parseCommit(oldHead).getTree().getId();
        ObjectId newHeadTree = revWalk.parseCommit(newHead).getTree().getId();

        // prepare the two iterators to compute the diff between
        try (ObjectReader reader = git.getRepository().newObjectReader()) {
            CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
            CanonicalTreeParser newTreeIter = new CanonicalTreeParser();

            oldTreeIter.reset(reader, oldHeadTree);
            newTreeIter.reset(reader, newHeadTree);

            // finally get the list of changed files
            List<DiffEntry> diffs = git.diff()
                .setNewTree(newTreeIter)
                .setOldTree(oldTreeIter)
                .call();
            for (DiffEntry entry : diffs) {
                switch (entry.getChangeType()) {
                    case MODIFY:
                        updatedFiles.add(entry.getNewPath());
                        logger.debug("Updated file: {}", entry.getNewPath());
                        break;
                    case DELETE:
                        deletedFiles.add(entry.getOldPath());
                        logger.debug("Deleted file: {}", entry.getOldPath());
                        break;
                    case RENAME:
                        deletedFiles.add(entry.getOldPath());
                        createdFiles.add(entry.getNewPath());
                        logger.debug("Renamed file: {} -> {}", entry.getOldPath(), entry.getNewPath());
                        break;
                    case COPY:
                        createdFiles.add(entry.getNewPath());
                        logger.debug("Copied file: {} -> {}", entry.getOldPath(), entry.getNewPath());
                        break;
                    default: // ADD
                        createdFiles.add(entry.getNewPath());
                        logger.debug("New file: {}", entry.getNewPath());
                        break;
                }
            }
        }

        return new ChangeSet(createdFiles, updatedFiles, deletedFiles);
    }

}
