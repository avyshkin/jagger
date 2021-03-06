/*
 * Copyright (c) 2010-2012 Grid Dynamics Consulting Services, Inc, All Rights Reserved
 * http://www.griddynamics.com
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of
 * the GNU Lesser General Public License as published by the Free Software Foundation; either
 * version 2.1 of the License, or any later version.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.griddynamics.jagger.engine.e1.scenario;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.griddynamics.jagger.coordinator.Command;
import com.griddynamics.jagger.coordinator.Coordination;
import com.griddynamics.jagger.coordinator.NodeId;
import com.griddynamics.jagger.coordinator.RemoteExecutor;
import com.griddynamics.jagger.engine.e1.process.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

public class DefaultWorkloadController implements WorkloadController {
    private static final int TIMEOUT = 3600000;
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkloadController.class);
    private final String sessionId;
    private final String taskId;
    private final Map<NodeId, RemoteExecutor> remotes;

    private final WorkloadTask task;
    private Progress progress;
    private Map<NodeId, String> processes;
    private Map<NodeId, Integer> threads;
    private Map<NodeId, Integer> delays;
    private Map<NodeId, Integer> poolSize;

    public DefaultWorkloadController(String sessionId, String taskId, WorkloadTask task, Map<NodeId, RemoteExecutor> remotes) {
        this.sessionId = Preconditions.checkNotNull(sessionId);
        this.taskId = Preconditions.checkNotNull(taskId);
        this.task = Preconditions.checkNotNull(task);
        this.remotes = ImmutableMap.copyOf(remotes);

        progress = Progress.IDLE;
        processes = Maps.newHashMap();
        threads = Maps.newHashMap();
        delays = Maps.newHashMap();
    }

    @Override
    public Set<NodeId> getNodes() {
        return remotes.keySet();
    }

    @Override
    public WorkloadExecutionStatus getStatus() {
        Preconditions.checkState(progress == Progress.STARTED, "Workload should be started to get status");

        log.debug("Workload status requested");
        WorkloadExecutionStatusBuilder builder = new WorkloadExecutionStatusBuilder(task);

        for (Map.Entry<NodeId, RemoteExecutor> entry : remotes.entrySet()) {
            Long pollTime = System.currentTimeMillis();

            NodeId id = entry.getKey();
            RemoteExecutor remote = entry.getValue();

            Integer samples = 0;
            String processId = processes.get(id);
            if (processId != null) {
                samples = remote.runSyncWithTimeout(PollWorkloadProcessStatus.create(sessionId, processId), Coordination.<Command<Integer>>doNothing(), TIMEOUT);
            }

            Integer threadsOnNode = threads.get(id);
            Integer delay = delays.get(id);

            log.debug("{} Polled status: node {}, threads on node {}, samples executed {} with delay {}", new Object[]{pollTime, id, threadsOnNode, samples, delay});

            builder.addNodeInfo(id, threadsOnNode, samples, delay, pollTime);
        }

        return builder.build();
    }

    @Override
    public void startWorkload(Map<NodeId, Integer> poolSize) {
        Preconditions.checkState(progress == Progress.IDLE, "Workload should be idle to get started");

        log.debug("Workload start requested");

        for (NodeId nodeId : remotes.keySet()) {
            threads.put(nodeId, 0);
            delays.put(nodeId, 0);
        }

        this.poolSize = poolSize;
        progress = Progress.STARTED;
    }

    @Override
    public void adjustConfiguration(NodeId id, WorkloadConfiguration newConfiguration) {
        Preconditions.checkState(progress == Progress.STARTED, "Workload should be started to get adjust task number");

        log.debug("Adjusting task number with threads {} on node {} is requested", threads, id);

        boolean workloadStarted = processes.containsKey(id);

        if (workloadStarted) {
            log.debug("Process is started. Going to change workload configuration");
            changeWorkload(id, newConfiguration);
        } else {
            log.debug("Process is not started. Going to start workload");
            startWorkload(id, newConfiguration);
        }

    }

    @Override
    public void stopWorkload() {
        Preconditions.checkState(progress == Progress.STARTED, "Workload should be started to stop processes");

        log.debug("Workload stop requested");

        for (Map.Entry<NodeId, String> entry : processes.entrySet()) {
            NodeId id = entry.getKey();
            String processId = entry.getValue();

            RemoteExecutor executor = remotes.get(id);
            StopWorkloadProcess stop = StopWorkloadProcess.create(sessionId, processId);

            log.debug("Going to stop process {} on node {}", processId, id);
            executor.runSyncWithTimeout(stop, Coordination.<Command>doNothing(), TIMEOUT);
            log.debug("Process {} is stopped on node {}", processId, id);
        }

        log.debug("Workload stopped");
        progress = Progress.STOPPED;
    }

    private void changeWorkload(NodeId node, WorkloadConfiguration newConfiguration) {
        String processId = processes.get(node);
        RemoteExecutor remote = remotes.get(node);
        remote.runSyncWithTimeout(ChangeWorkloadConfiguration.create(sessionId, processId, newConfiguration), Coordination.<Command>doNothing(), TIMEOUT);
        threads.put(node, newConfiguration.getThreads());
        delays.put(node, newConfiguration.getDelay());
    }

    private void startWorkload(NodeId node, WorkloadConfiguration configuration) {
        ScenarioContext scenarioContext = new ScenarioContext(taskId, task.getName(), task.getVersion(), configuration);
        Integer nodePoolSize = poolSize.get(node);
        if (nodePoolSize == null) {
            nodePoolSize = 1;
        }
        StartWorkloadProcess start = StartWorkloadProcess.create(sessionId, scenarioContext, nodePoolSize);
        start.setScenarioFactory(task.getScenarioFactory());
        start.setCollectors(task.getCollectors());

        log.debug("Going to start process {} on node {}", start, node);

        RemoteExecutor remote = remotes.get(node);
        String processId = remote.runSyncWithTimeout(start, Coordination.<StartWorkloadProcess>doNothing(), TIMEOUT);

        log.debug("Process with id {} is started on node {}", processId, node);
        processes.put(node, processId);
        threads.put(node, configuration.getThreads());
        delays.put(node, configuration.getDelay());
    }

    private static enum Progress {
        IDLE, STARTED, STOPPED
    }
}
