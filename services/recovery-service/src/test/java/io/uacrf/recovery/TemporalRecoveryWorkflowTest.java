package io.uacrf.recovery;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.uacrf.contracts.authority.BoundedRecoveryAuthorization;
import io.uacrf.recovery.adapter.CloudTrafficFailoverAdapter;
import io.uacrf.recovery.adapter.KubernetesLocalRollbackAdapter;
import io.uacrf.recovery.service.CrossLayerValidator;
import io.uacrf.recovery.temporal.RecoveryActivitiesImpl;
import io.uacrf.recovery.temporal.RecoveryWorkflow;
import io.uacrf.recovery.temporal.RecoveryWorkflowImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TemporalRecoveryWorkflowTest {

    private static BoundedRecoveryAuthorization sampleAuth(String audience, String actionType) {
        return new BoundedRecoveryAuthorization(
                "1.0", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                actionType, List.of("urn:uacrf:svc:checkout"),
                "11111111-1111-7111-8111-111111111111", "22222222-2222-7222-8222-222222222222",
                "rev-sample-local-0001", "sha256:sub", 1, 100, List.of("DELETE_DATA"),
                "val-plan", "comp-plan", "APPROVAL_REQUIRED",
                List.of("33333333-3333-7333-8333-333333333303"),
                audience, UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES)
        );
    }

    @Test
    void executesDurableTemporalWorkflowAndCompensatesWhenP6ValidationFails() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            Worker worker = env.newWorker("uacrf-recovery-tq");
            worker.registerWorkflowImplementationTypes(RecoveryWorkflowImpl.class);
            worker.registerActivitiesImplementations(new RecoveryActivitiesImpl(
                    List.of(new KubernetesLocalRollbackAdapter(), new CloudTrafficFailoverAdapter()),
                    new CrossLayerValidator()
            ));
            env.start();

            RecoveryWorkflow passWf = env.getWorkflowClient().newWorkflowStub(
                    RecoveryWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("uacrf-recovery-tq").build()
            );
            var ok = passWf.executeBoundedRecovery(
                    sampleAuth("multi-cloud-traffic-adapter", "OCI_BACKEND_DRAIN"),
                    "multi-cloud-traffic-adapter", "urn:uacrf:svc:checkout", "v42", false
            );
            assertEquals("SUCCEEDED", ok.workflowState());
            assertFalse(ok.compensated());
            assertEquals(8, ok.validationSteps().size());

            RecoveryWorkflow failWf = env.getWorkflowClient().newWorkflowStub(
                    RecoveryWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("uacrf-recovery-tq").build()
            );
            var comp = failWf.executeBoundedRecovery(
                    sampleAuth("k8s-rollback-adapter", "K8S_ROLLBACK_DEPLOYMENT"),
                    "k8s-rollback-adapter", "urn:uacrf:svc:checkout", "v42", true
            );
            assertEquals("COMPENSATED", comp.workflowState());
            assertTrue(comp.compensated());
            assertTrue(comp.summary().contains("Bounded compensation"));
        }
    }
}
