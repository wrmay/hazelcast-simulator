package com.hazelcast.simulator.protocol;

import com.hazelcast.simulator.protocol.configuration.ClientConfiguration;
import com.hazelcast.simulator.protocol.connector.AgentConnector;
import com.hazelcast.simulator.protocol.connector.CoordinatorConnector;
import com.hazelcast.simulator.protocol.connector.ServerConnector;
import com.hazelcast.simulator.protocol.connector.WorkerConnector;
import com.hazelcast.simulator.protocol.core.AddressLevel;
import com.hazelcast.simulator.protocol.core.Response;
import com.hazelcast.simulator.protocol.core.ResponseFuture;
import com.hazelcast.simulator.protocol.core.ResponseType;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.core.SimulatorMessage;
import com.hazelcast.simulator.protocol.exception.ExceptionLogger;
import com.hazelcast.simulator.protocol.operation.IntegrationTestOperation;
import com.hazelcast.simulator.protocol.operation.OperationType;
import com.hazelcast.simulator.protocol.operation.SimulatorOperation;
import com.hazelcast.simulator.protocol.processors.OperationProcessor;
import com.hazelcast.simulator.protocol.processors.TestOperationProcessor;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.hazelcast.simulator.protocol.core.SimulatorAddress.COORDINATOR;
import static com.hazelcast.simulator.protocol.operation.OperationHandler.encodeOperation;
import static com.hazelcast.simulator.protocol.operation.OperationType.getOperationType;
import static com.hazelcast.simulator.utils.CommonUtils.joinThreads;
import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

public class ProtocolUtil {

    public static final long DEFAULT_TEST_TIMEOUT_MILLIS = 5000;

    private static final int AGENT_START_PORT = 10000;
    private static final int WORKER_START_PORT = 10100;

    private static final Logger LOGGER = Logger.getLogger(ProtocolUtil.class);
    private static final Logger ROOT_LOGGER = Logger.getRootLogger();
    private static final AtomicReference<Level> LOGGER_LEVEL = new AtomicReference<Level>();

    private static final AddressLevel MIN_ADDRESS_LEVEL = AddressLevel.AGENT;
    private static final int MIN_ADDRESS_LEVEL_VALUE = MIN_ADDRESS_LEVEL.toInt();

    private static final SimulatorOperation OPERATION = new IntegrationTestOperation(IntegrationTestOperation.TEST_DATA);
    private static final OperationType OPERATION_TYPE = getOperationType(OPERATION);
    private static final String OPERATION_JSON = encodeOperation(OPERATION);

    private static final Random RANDOM = new Random();
    private static final AtomicLong MESSAGE_ID = new AtomicLong();

    private static final ExceptionLogger EXCEPTION_LOGGER = mock(ExceptionLogger.class);

    private static CoordinatorConnector coordinatorConnector;
    private static List<AgentConnector> agentConnectors = new ArrayList<AgentConnector>();
    private static List<WorkerConnector> workerConnectors = new ArrayList<WorkerConnector>();

    static void setLogLevel(Level level) {
        if (LOGGER_LEVEL.compareAndSet(null, ROOT_LOGGER.getLevel())) {
            ROOT_LOGGER.setLevel(level);
        }
    }

    static void resetLogLevel() {
        Level level = LOGGER_LEVEL.get();
        if (level != null && LOGGER_LEVEL.compareAndSet(level, null)) {
            ROOT_LOGGER.setLevel(level);
        }
    }

    static void startSimulatorComponents(int numberOfAgents, int numberOfWorkers, int numberOfTests) {
        for (int agentIndex = 1; agentIndex <= numberOfAgents; agentIndex++) {
            int workerStartPort = WORKER_START_PORT + (100 * (agentIndex - 1));
            for (int workerIndex = 1; workerIndex <= numberOfWorkers; workerIndex++) {
                workerConnectors.add(startWorker(workerIndex, agentIndex, workerStartPort + workerIndex, numberOfTests));
            }

            int agentPort = AGENT_START_PORT + agentIndex;
            agentConnectors.add(startAgent(agentIndex, agentPort, "127.0.0.1", workerStartPort, numberOfWorkers));
        }

        coordinatorConnector = startCoordinator("127.0.0.1", AGENT_START_PORT, numberOfAgents);
    }

    static void stopSimulatorComponents() {
        List<Thread> shutdownThreads = new ArrayList<Thread>();

        LOGGER.info("Shutdown of Coordinator...");
        if (coordinatorConnector != null) {
            coordinatorConnector.shutdown();
            coordinatorConnector = null;
        }

        LOGGER.info("Shutdown of Agents...");
        shutdownServerConnectors(agentConnectors, shutdownThreads);

        LOGGER.info("Shutdown of Workers...");
        shutdownServerConnectors(workerConnectors, shutdownThreads);

        LOGGER.info("Waiting for shutdown threads...");
        joinThreads(shutdownThreads);

        LOGGER.info("Shutdown complete!");
    }

    private static <C extends ServerConnector> void shutdownServerConnectors(List<C> connectors, List<Thread> shutdownThreads) {
        for (final C connector : connectors) {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    connector.shutdown();
                }
            };
            thread.start();
            shutdownThreads.add(thread);
        }
        connectors.clear();
    }

    static WorkerConnector startWorker(int addressIndex, int parentAddressIndex, int port, int numberOfTests) {
        WorkerConnector workerConnector = WorkerConnector.createInstance(addressIndex, parentAddressIndex, port, true);

        OperationProcessor processor = new TestOperationProcessor(EXCEPTION_LOGGER);
        for (int testIndex = 1; testIndex <= numberOfTests; testIndex++) {
            workerConnector.addTest(testIndex, processor);
        }

        workerConnector.start();
        return workerConnector;
    }

    static AgentConnector startAgent(int addressIndex, int port, String workerHost, int workerStartPort, int numberOfWorkers) {
        AgentConnector agentConnector = AgentConnector.createInstance(addressIndex, port);
        for (int workerIndex = 1; workerIndex <= numberOfWorkers; workerIndex++) {
            agentConnector.addWorker(workerIndex, workerHost, workerStartPort + workerIndex);
        }

        agentConnector.start();
        return agentConnector;
    }

    static CoordinatorConnector startCoordinator(String agentHost, int agentStartPort, int numberOfAgents) {
        CoordinatorConnector coordinatorConnector = new CoordinatorConnector();
        for (int i = 1; i <= numberOfAgents; i++) {
            coordinatorConnector.addAgent(i, agentHost, agentStartPort + i);
        }

        return coordinatorConnector;
    }

    static void resetMessageId() {
        MESSAGE_ID.set(0);
    }

    static SimulatorMessage buildRandomMessage(int maxAddressIndex) {
        int addressLevelValue = MIN_ADDRESS_LEVEL_VALUE + RANDOM.nextInt(AddressLevel.values().length - MIN_ADDRESS_LEVEL_VALUE);
        AddressLevel addressLevel = AddressLevel.fromInt(addressLevelValue);

        int agentIndex = RANDOM.nextInt(maxAddressIndex + 1);
        int workerIndex = RANDOM.nextInt(maxAddressIndex + 1);
        int testIndex = RANDOM.nextInt(maxAddressIndex + 1);

        switch (addressLevel) {
            case COORDINATOR:
                return buildMessage(COORDINATOR);
            case AGENT:
                return buildMessage(new SimulatorAddress(addressLevel, agentIndex, 0, 0));
            case WORKER:
                return buildMessage(new SimulatorAddress(addressLevel, agentIndex, workerIndex, 0));
            case TEST:
                return buildMessage(new SimulatorAddress(addressLevel, agentIndex, workerIndex, testIndex));
            default:
                throw new IllegalArgumentException("Unsupported addressLevel: " + addressLevel);
        }
    }

    static SimulatorMessage buildMessage(SimulatorAddress destination) {
        return buildMessage(destination, COORDINATOR);
    }

    static SimulatorMessage buildMessage(SimulatorAddress destination, SimulatorAddress source) {
        return buildMessage(destination, source, OPERATION_TYPE, OPERATION_JSON);
    }

    static SimulatorMessage buildMessage(SimulatorAddress destination, SimulatorAddress source,
                                         OperationType operationType, String operationData) {
        return new SimulatorMessage(destination, source, MESSAGE_ID.incrementAndGet(), operationType, operationData);
    }

    static Response sendFromCoordinator(SimulatorMessage message) throws Exception {
        return coordinatorConnector.send(message);
    }

    static CoordinatorConnector getCoordinatorConnector() {
        return coordinatorConnector;
    }

    static AgentConnector getAgentConnector(int index) {
        return agentConnectors.get(index);
    }

    static WorkerConnector getWorkerConnector(int index) {
        return workerConnectors.get(index);
    }

    static void assertSingleTarget(Response response, SimulatorAddress destination, ResponseType responseType) {
        assertAllTargets(response, SimulatorAddress.COORDINATOR, destination, responseType, 1);
    }

    static void assertSingleTarget(Response response, SimulatorAddress source, SimulatorAddress destination,
                                   ResponseType responseType) {
        assertAllTargets(response, source, destination, responseType, 1);
    }

    static void assertAllTargets(Response response, SimulatorAddress destination, ResponseType responseType, int responseCount) {
        assertAllTargets(response, SimulatorAddress.COORDINATOR, destination, responseType, responseCount);
    }

    static void assertAllTargets(Response response, SimulatorAddress source, SimulatorAddress destination,
                                 ResponseType responseType, int responseCount) {
        assertEquals(source, response.getDestination());
        assertEquals(responseCount, response.entrySet().size());
        for (Map.Entry<SimulatorAddress, ResponseType> entry : response.entrySet()) {
            assertEquals(responseType, entry.getValue());
            assertEquals(destination.getAddressLevel(), entry.getKey().getAddressLevel());
        }
    }

    static void assertEmptyFutureMaps() {
        LOGGER.info("Asserting that all future maps are empty...");

        for (ClientConfiguration configuration : coordinatorConnector.getConfigurationList()) {
            ConcurrentMap<String, ResponseFuture> futureMap = configuration.getFutureMap();
            int futureMapSize = futureMap.size();
            if (futureMapSize > 0) {
                LOGGER.error("Future entries: " + futureMap.toString());
                fail(format("FutureMap of ClientConnector %s is not empty", configuration.getRemoteAddress()));
            }
        }
        assertEmptyFutureMaps(agentConnectors, "AgentConnector");
        assertEmptyFutureMaps(workerConnectors, "WorkerConnector");

        LOGGER.info("Done!");
    }

    private static <C extends ServerConnector> void assertEmptyFutureMaps(List<C> connectorList, String connectorName) {
        for (C connector : connectorList) {
            ConcurrentMap<String, ResponseFuture> futureMap = connector.getConfiguration().getFutureMap();
            int futureMapSize = futureMap.size();
            if (futureMapSize > 0) {
                LOGGER.error("Future entries: " + futureMap.toString());
                fail(format("FutureMap of %s %s is not empty", connectorName, connector.getAddress()));
            }
        }
    }
}
