package com.netflix.conductor.core.execution;

import com.google.common.annotations.VisibleForTesting;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.dao.ExecutionDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * A helper service that tries to keep ExecutionDAO and QueueDAO in sync, based on the
 * task or workflow state.
 *
 * This service expects that the underlying Queueing layer implements QueueDAO.containsMessage method. This can be controlled
 * with Configuration.isWorkflowRepairServiceEnabled() property.
 */
public class WorkflowRepairService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRepairService.class);

    private final ExecutionDAO executionDAO;
    private final QueueDAO queueDAO;

    private final Predicate<Task> isSystemTask = task -> WorkflowSystemTask.is(task.getTaskType());

    @Inject
    public WorkflowRepairService(
            ExecutionDAO executionDAO,
            QueueDAO queueDAO
    ) {
        this.executionDAO = executionDAO;
        this.queueDAO = queueDAO;
    }

    public boolean verifyAndRepairWorkflow(String workflowId, boolean includeTasks) {
        Workflow workflow = executionDAO.getWorkflow(workflowId, includeTasks);
        AtomicBoolean repaired = new AtomicBoolean(false);
        repaired.set(verifyAndRepairDeciderQueue(workflow));
        if (includeTasks) {
            workflow.getTasks().forEach(task -> {
                repaired.set(verifyAndRepairTask(task));
            });
        }
        return repaired.get();
    }

    public void verifyAndRepairWorkflowTasks(String workflowId) {
        Workflow workflow = executionDAO.getWorkflow(workflowId, true);
        workflow.getTasks().forEach(task -> verifyAndRepairTask(task));
    }

    private boolean verifyAndRepairDeciderQueue(Workflow workflow) {
        if (workflow.getStatus().equals(Workflow.WorkflowStatus.RUNNING)) {
            String queueName = WorkflowExecutor.DECIDER_QUEUE;
            if (!queueDAO.containsMessage(queueName, workflow.getWorkflowId())) {
                queueDAO.push(queueName, workflow.getWorkflowId(), 30);
                Monitors.recordQueueMessageRepushFromRepairService(queueName);
                return true;
            }
        }
        return false;
    }

    /**
     * Verify if ExecutionDAO and QueueDAO agree for the provided task.
     * @param task
     * @return
     */
    @VisibleForTesting
    protected boolean verifyAndRepairTask(Task task) {
        WorkflowSystemTask workflowSystemTask = WorkflowSystemTask.get(task.getTaskType());
        if (task.getStatus().equals(Task.Status.SCHEDULED)) {
            if (isSystemTask.test(task) && !workflowSystemTask.isAsync()) {
                return false;
            }
            // Ensure QueueDAO contains this taskId
            if (!queueDAO.containsMessage(task.getTaskDefName(), task.getTaskId())) {
                queueDAO.push(task.getTaskDefName(), task.getTaskId(), task.getCallbackAfterSeconds());
                Monitors.recordQueueMessageRepushFromRepairService(task.getTaskDefName());
                return true;
            }
        }
        return false;
    }
}
