/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.coordinator;

import com.hazelcast.simulator.common.GitInfo;
import com.hazelcast.simulator.common.JavaProfiler;
import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.protocol.connector.CoordinatorConnector;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.registry.AgentData;
import com.hazelcast.simulator.protocol.registry.ComponentRegistry;
import com.hazelcast.simulator.test.TestCase;
import com.hazelcast.simulator.test.TestPhase;
import com.hazelcast.simulator.test.TestSuite;
import com.hazelcast.simulator.utils.Bash;
import com.hazelcast.simulator.utils.CommandLineExitException;
import com.hazelcast.simulator.utils.ThreadSpawner;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static com.hazelcast.simulator.coordinator.CoordinatorUtils.FINISHED_WORKER_TIMEOUT_SECONDS;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.getElapsedSeconds;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.getTestPhaseSyncMap;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.initMemberLayout;
import static com.hazelcast.simulator.coordinator.CoordinatorUtils.waitForWorkerShutdown;
import static com.hazelcast.simulator.protocol.configuration.Ports.AGENT_PORT;
import static com.hazelcast.simulator.utils.CloudProviderUtils.isEC2;
import static com.hazelcast.simulator.utils.CommonUtils.exitWithError;
import static com.hazelcast.simulator.utils.CommonUtils.getSimulatorVersion;
import static com.hazelcast.simulator.utils.CommonUtils.rethrow;
import static com.hazelcast.simulator.utils.CommonUtils.sleepSeconds;
import static com.hazelcast.simulator.utils.FileUtils.getFilesFromClassPath;
import static com.hazelcast.simulator.utils.FileUtils.getSimulatorHome;
import static com.hazelcast.simulator.utils.FormatUtils.HORIZONTAL_RULER;
import static com.hazelcast.simulator.utils.FormatUtils.secondsToHuman;
import static com.hazelcast.simulator.utils.HarakiriMonitorUtils.getStartHarakiriMonitorCommandOrNull;
import static com.hazelcast.simulator.utils.SimulatorUtils.loadComponentRegister;
import static java.lang.String.format;

public final class Coordinator {

    static final File SIMULATOR_HOME = getSimulatorHome();

    private static final File WORKING_DIRECTORY = new File(System.getProperty("user.dir"));
    private static final File UPLOAD_DIRECTORY = new File(WORKING_DIRECTORY, "upload");

    private static final Logger LOGGER = Logger.getLogger(Coordinator.class);

    private final PerformanceStateContainer performanceStateContainer = new PerformanceStateContainer();
    private final TestHistogramContainer testHistogramContainer = new TestHistogramContainer(performanceStateContainer);

    private final CoordinatorParameters coordinatorParameters;
    private final ClusterLayoutParameters clusterLayoutParameters;
    private final WorkerParameters workerParameters;
    private final TestSuite testSuite;

    private final ComponentRegistry componentRegistry;
    private final FailureContainer failureContainer;

    private final SimulatorProperties props;
    private final Bash bash;

    private RemoteClient remoteClient;
    private CoordinatorConnector coordinatorConnector;

    public Coordinator(CoordinatorParameters coordinatorParameters, ClusterLayoutParameters clusterLayoutParameters,
                       WorkerParameters workerParameters, TestSuite testSuite) {
        this.coordinatorParameters = coordinatorParameters;
        this.clusterLayoutParameters = clusterLayoutParameters;
        this.workerParameters = workerParameters;
        this.testSuite = testSuite;

        this.componentRegistry = loadComponentRegister(coordinatorParameters.getAgentsFile());
        this.failureContainer = new FailureContainer(testSuite.getId(), componentRegistry);

        this.props = coordinatorParameters.getSimulatorProperties();
        this.bash = new Bash(props);

        int agentCount = componentRegistry.agentCount();
        clusterLayoutParameters.initMemberWorkerCount(agentCount);
        workerParameters.initMemberHzConfig(componentRegistry, props);
        workerParameters.initClientHzConfig(componentRegistry);

        boolean performanceEnabled = workerParameters.isMonitorPerformance();
        int performanceIntervalSeconds = workerParameters.getWorkerPerformanceMonitorIntervalSeconds();
        LOGGER.info(format("Performance monitor enabled: %s (%d seconds)", performanceEnabled, performanceIntervalSeconds));
        LOGGER.info(format("Total number of agents: %s", agentCount));
        LOGGER.info(format("Total number of Hazelcast member workers: %s", clusterLayoutParameters.getMemberWorkerCount()));
        LOGGER.info(format("Total number of Hazelcast client workers: %s", clusterLayoutParameters.getClientWorkerCount()));
    }

    CoordinatorParameters getCoordinatorParameters() {
        return coordinatorParameters;
    }

    ClusterLayoutParameters getClusterLayoutParameters() {
        return clusterLayoutParameters;
    }

    WorkerParameters getWorkerParameters() {
        return workerParameters;
    }

    TestSuite getTestSuite() {
        return testSuite;
    }

    // just for testing
    FailureContainer getFailureContainer() {
        return failureContainer;
    }

    // just for testing
    void setRemoteClient(RemoteClient remoteClient) {
        this.remoteClient = remoteClient;
    }

    private void run() throws Exception {
        try {
            uploadFiles();

            startAgents();
            startWorkers();

            runTestSuite();
            logFailureInfo();
        } finally {
            shutdown();
        }
    }

    private void uploadFiles() {
        uploadUploadDirectory();
        uploadWorkerClassPath();
        uploadYourKitIfNeeded();
        // TODO: copy the Hazelcast JARs
    }

    private void startAgents() {
        echoLocal("Starting %s Agents", componentRegistry.agentCount());
        ThreadSpawner spawner = new ThreadSpawner("startAgents", true);
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    startAgent(agentData.getAddressIndex(), agentData.getPublicAddress());
                }
            });
        }
        spawner.awaitCompletion();
        echoLocal("Successfully started agents on %s boxes", componentRegistry.agentCount());

        try {
            startCoordinatorConnector();
        } catch (Exception e) {
            throw new CommandLineExitException("Could not start CoordinatorConnector", e);
        }

        remoteClient = new RemoteClient(coordinatorConnector, componentRegistry);
        remoteClient.initTestSuite(testSuite);
    }

    private void startAgent(int addressIndex, String ip) {
        echoLocal("Killing Java processes on %s", ip);
        bash.killAllJavaProcesses(ip);

        echoLocal("Starting Agent on %s", ip);
        String mandatoryParameters = format("--addressIndex %d --publicAddress %s", addressIndex, ip);
        String optionalParameters = "";
        if (isEC2(props.get("CLOUD_PROVIDER"))) {
            optionalParameters = format(" --cloudProvider %s --cloudIdentity %s --cloudCredential %s",
                    props.get("CLOUD_PROVIDER"),
                    props.get("CLOUD_IDENTITY"),
                    props.get("CLOUD_CREDENTIAL"));
        }
        bash.ssh(ip, format("nohup hazelcast-simulator-%s/bin/agent %s%s > agent.out 2> agent.err < /dev/null &",
                getSimulatorVersion(), mandatoryParameters, optionalParameters));

        bash.ssh(ip, format("hazelcast-simulator-%s/bin/.await-file-exists agent.pid", getSimulatorVersion()));
    }

    private void startCoordinatorConnector() {
        coordinatorConnector = new CoordinatorConnector(performanceStateContainer, testHistogramContainer, failureContainer);
        ThreadSpawner spawner = new ThreadSpawner("startCoordinatorConnector", true);
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    coordinatorConnector.addAgent(agentData.getAddressIndex(), agentData.getPublicAddress(), AGENT_PORT);
                }
            });
        }
        spawner.awaitCompletion();
    }

    private void uploadUploadDirectory() {
        try {
            if (!UPLOAD_DIRECTORY.exists()) {
                LOGGER.debug("Skipping upload, since no upload file in working directory");
                return;
            }

            LOGGER.info(format("Starting uploading '%s' to agents", UPLOAD_DIRECTORY.getAbsolutePath()));
            List<File> files = getFilesFromClassPath(UPLOAD_DIRECTORY.getAbsolutePath());
            for (AgentData agentData : componentRegistry.getAgents()) {
                String ip = agentData.getPublicAddress();
                LOGGER.info(format("Uploading '%s' to agent %s", UPLOAD_DIRECTORY.getAbsolutePath(), ip));
                for (File file : files) {
                    bash.execute(format("rsync -avv -e \"ssh %s\" %s %s@%s:hazelcast-simulator-%s/workers/%s/",
                            props.get("SSH_OPTIONS", ""),
                            file,
                            props.get("USER"),
                            ip,
                            getSimulatorVersion(),
                            testSuite.getId()));
                }
                LOGGER.info("    " + ip + " copied");
            }
            LOGGER.info(format("Finished uploading '%s' to agents", UPLOAD_DIRECTORY.getAbsolutePath()));
        } catch (Exception e) {
            throw new CommandLineExitException("Could not copy upload directory to agents", e);
        }
    }

    private void uploadWorkerClassPath() {
        String workerClassPath = coordinatorParameters.getWorkerClassPath();
        if (workerClassPath == null) {
            return;
        }

        try {
            List<File> upload = getFilesFromClassPath(workerClassPath);
            LOGGER.info(format("Copying %d files from workerClasspath '%s' to agents", upload.size(), workerClassPath));
            for (AgentData agentData : componentRegistry.getAgents()) {
                String ip = agentData.getPublicAddress();
                for (File file : upload) {
                    bash.execute(
                            format("rsync --ignore-existing -avv -e \"ssh %s\" %s %s@%s:hazelcast-simulator-%s/workers/%s/lib",
                                    props.get("SSH_OPTIONS", ""),
                                    file.getAbsolutePath(),
                                    props.get("USER"),
                                    ip,
                                    getSimulatorVersion(),
                                    testSuite.getId()));
                }
                LOGGER.info("    " + ip + " copied");
            }
            LOGGER.info(format("Finished copying workerClasspath '%s' to agents", workerClassPath));
        } catch (Exception e) {
            throw new CommandLineExitException("Could not upload worker classpath to agents", e);
        }
    }

    private void uploadYourKitIfNeeded() {
        if (workerParameters.getProfiler() != JavaProfiler.YOURKIT) {
            return;
        }

        // TODO: in the future we'll only upload the requested YourKit library (32 or 64 bit)
        LOGGER.info("Uploading YourKit dependencies to agents");
        for (AgentData agentData : componentRegistry.getAgents()) {
            String ip = agentData.getPublicAddress();
            bash.ssh(ip, format("mkdir -p hazelcast-simulator-%s/yourkit", getSimulatorVersion()));

            bash.execute(format("rsync --ignore-existing -avv -e \"ssh %s\" %s/yourkit %s@%s:hazelcast-simulator-%s/",
                    props.get("SSH_OPTIONS", ""),
                    getSimulatorHome().getAbsolutePath(),
                    props.get("USER"),
                    ip,
                    getSimulatorVersion()));
        }
    }

    private void startWorkers() {
        int memberWorkerCount = clusterLayoutParameters.getMemberWorkerCount();
        int clientWorkerCount = clusterLayoutParameters.getClientWorkerCount();
        int totalWorkerCount = memberWorkerCount + clientWorkerCount;

        List<AgentMemberLayout> agentMemberLayouts = initMemberLayout(componentRegistry, workerParameters,
                clusterLayoutParameters.getDedicatedMemberMachineCount(), memberWorkerCount, clientWorkerCount);

        long started = System.nanoTime();
        try {
            echo("Killing all remaining workers");
            remoteClient.terminateWorkers(false);
            echo("Successfully killed all remaining workers");

            echo("Starting %d workers (%d members, %d clients)", totalWorkerCount, memberWorkerCount, clientWorkerCount);
            remoteClient.createWorkers(agentMemberLayouts, true);
            echo("Successfully started workers");
        } catch (Exception e) {
            while (failureContainer.getFailureCount() == 0) {
                sleepSeconds(1);
            }
            throw new CommandLineExitException("Failed to start workers", e);
        }

        long elapsed = getElapsedSeconds(started);
        LOGGER.info((format("Successfully started a grand total of %s worker JVMs (%s seconds)", totalWorkerCount, elapsed)));
    }

    void runTestSuite() {
        boolean isParallel = coordinatorParameters.isParallel();
        int testCount = testSuite.size();
        int maxTestCaseIdLength = testSuite.getMaxTestCaseIdLength();

        TestPhase lastTestPhaseToSync = coordinatorParameters.getLastTestPhaseToSync();
        ConcurrentMap<TestPhase, CountDownLatch> testPhaseSyncs = getTestPhaseSyncMap(isParallel, testCount, lastTestPhaseToSync);

        echo("Starting testsuite: %s", testSuite.getId());
        logTestSuiteDuration();

        echo(HORIZONTAL_RULER);
        echo("Running %s tests (%s)", testCount, isParallel ? "parallel" : "sequentially");
        echo(HORIZONTAL_RULER);

        List<TestCaseRunner> testCaseRunners = new ArrayList<TestCaseRunner>(testCount);
        for (TestCase testCase : testSuite.getTestCaseList()) {
            echo(format("Configuration for test: %s%n%s", testCase.getId(), testCase));
            TestCaseRunner runner = new TestCaseRunner(testCase, testSuite, this, remoteClient, failureContainer,
                    performanceStateContainer, maxTestCaseIdLength, testPhaseSyncs);
            testCaseRunners.add(runner);
        }
        echo(HORIZONTAL_RULER);

        long started = System.nanoTime();
        if (isParallel) {
            runParallel(testCaseRunners);
        } else {
            runSequential(testCaseRunners);
        }

        remoteClient.terminateWorkers(true);
        Set<SimulatorAddress> finishedWorkers = failureContainer.getFinishedWorkers();
        if (!waitForWorkerShutdown(componentRegistry.workerCount(), finishedWorkers, FINISHED_WORKER_TIMEOUT_SECONDS)) {
            LOGGER.warn(format("Unfinished workers: %s", componentRegistry.getMissingWorkers(finishedWorkers).toString()));
        }

        performanceStateContainer.logDetailedPerformanceInfo();
        for (TestCase testCase : testSuite.getTestCaseList()) {
            testHistogramContainer.createProbeResults(testSuite.getId(), testCase.getId());
        }

        echo(format("Total running time: %s seconds", getElapsedSeconds(started)));
    }

    private void logTestSuiteDuration() {
        int testDuration = testSuite.getDurationSeconds();
        if (testDuration > 0) {
            echo("Running time per test: %s", secondsToHuman(testDuration));
            int totalDuration = (coordinatorParameters.isParallel()) ? testDuration : testDuration * testSuite.size();
            if (testSuite.isWaitForTestCase()) {
                echo("Testsuite will run until tests are finished for a maximum time of: %s", secondsToHuman(totalDuration));
            } else {
                echo("Expected total testsuite time: %s", secondsToHuman(totalDuration));
            }
        } else if (testSuite.isWaitForTestCase()) {
            echo("Testsuite will run until tests are finished");
        }
    }

    private void runParallel(List<TestCaseRunner> testCaseRunners) {
        ThreadSpawner spawner = new ThreadSpawner("runParallel", true);
        for (final TestCaseRunner runner : testCaseRunners) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean success = runner.run();
                        if (!success && testSuite.isFailFast()) {
                            LOGGER.info("Aborting testsuite due to failure (not implemented yet)");
                            // FIXME: we should abort here as logged
                        }
                    } catch (Exception e) {
                        throw rethrow(e);
                    }
                }
            });
        }
        spawner.awaitCompletion();
    }

    private void runSequential(List<TestCaseRunner> testCaseRunners) {
        for (TestCaseRunner runner : testCaseRunners) {
            boolean success = runner.run();
            if (!success && testSuite.isFailFast()) {
                LOGGER.info("Aborting testsuite due to failure");
                break;
            }
            if (!success || coordinatorParameters.isRefreshJvm()) {
                startWorkers();
            }
        }
    }

    private void logFailureInfo() {
        int failureCount = failureContainer.getFailureCount();
        if (failureCount > 0) {
            LOGGER.fatal(HORIZONTAL_RULER);
            LOGGER.fatal(failureCount + " failures have been detected!!!");
            LOGGER.fatal(HORIZONTAL_RULER);
            throw new CommandLineExitException(failureCount + " failures have been detected");
        }
        LOGGER.info(HORIZONTAL_RULER);
        LOGGER.info("No failures have been detected!");
        LOGGER.info(HORIZONTAL_RULER);
    }

    private void shutdown() throws Exception {
        if (coordinatorConnector != null) {
            LOGGER.info("Shutdown of ClientConnector...");
            coordinatorConnector.shutdown();
        }

        stopAgents();
    }

    private void stopAgents() {
        ThreadSpawner spawner = new ThreadSpawner("killAgents", true);
        final String startHarakiriMonitorCommand = getStartHarakiriMonitorCommandOrNull(props);

        echoLocal("Stopping %s Agents", componentRegistry.agentCount());
        for (final AgentData agentData : componentRegistry.getAgents()) {
            spawner.spawn(new Runnable() {
                @Override
                public void run() {
                    String ip = agentData.getPublicAddress();
                    echoLocal("Stopping Agent %s", ip);
                    bash.ssh(ip, format("hazelcast-simulator-%s/bin/.kill-from-pid-file agent.pid", getSimulatorVersion()));

                    if (startHarakiriMonitorCommand != null) {
                        LOGGER.info(format("Starting HarakiriMonitor on %s", ip));
                        bash.ssh(ip, startHarakiriMonitorCommand);
                    }
                }
            });
        }
        spawner.awaitCompletion();
        echoLocal("Successfully stopped %s Agents", componentRegistry.agentCount());
    }

    private void echoLocal(String msg, Object... args) {
        LOGGER.info(format(msg, args));
    }

    private void echo(String msg, Object... args) {
        echo(format(msg, args));
    }

    private void echo(String msg) {
        remoteClient.logOnAllAgents(msg);
        LOGGER.info(msg);
    }

    public static void main(String[] args) {
        try {
            LOGGER.info("Hazelcast Simulator Coordinator");
            LOGGER.info(format("Version: %s, Commit: %s, Build Time: %s",
                    getSimulatorVersion(), GitInfo.getCommitIdAbbrev(), GitInfo.getBuildTime()));
            LOGGER.info(format("SIMULATOR_HOME: %s", SIMULATOR_HOME));

            Coordinator coordinator = CoordinatorCli.init(args);

            LOGGER.info(format("Loading agents file: %s", coordinator.coordinatorParameters.getAgentsFile().getAbsolutePath()));
            LOGGER.info(format("HAZELCAST_VERSION_SPEC: %s", coordinator.props.getHazelcastVersionSpec()));

            coordinator.run();
        } catch (Exception e) {
            exitWithError(LOGGER, "Failed to run testsuite", e);
        }
    }
}
