package io.uacrf.ux;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PortalController.class)
class ExperienceUiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UacrfEdgeClient edgeClient;

    @Test
    void rendersOverviewAndDirectNavigationRoutes() throws Exception {
        when(edgeClient.getList(anyString(), any())).thenReturn(List.of());
        when(edgeClient.getObjectOrEmpty(anyString())).thenReturn(java.util.Map.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("overview"));

        mockMvc.perform(get("/journeys"))
                .andExpect(status().isOk())
                .andExpect(view().name("journeys"));

        mockMvc.perform(get("/agents"))
                .andExpect(status().isOk())
                .andExpect(view().name("agents"));

        mockMvc.perform(get("/dependencies"))
                .andExpect(status().isOk())
                .andExpect(view().name("dependencies"));

        mockMvc.perform(get("/ai-dependencies"))
                .andExpect(status().isOk())
                .andExpect(view().name("ai-dependencies"));

        mockMvc.perform(get("/evidence"))
                .andExpect(status().isOk())
                .andExpect(view().name("evidence"));

        mockMvc.perform(get("/settings/connections"))
                .andExpect(status().isOk())
                .andExpect(view().name("connections"));

        mockMvc.perform(get("/assess"))
                .andExpect(status().isOk())
                .andExpect(view().name("assess"));

        mockMvc.perform(get("/assess/aaaa1111-1111-7111-8111-111111111101/report"))
                .andExpect(status().isOk())
                .andExpect(view().name("assess-report"));

        mockMvc.perform(get("/control"))
                .andExpect(status().isOk())
                .andExpect(view().name("control"));

        mockMvc.perform(get("/recover"))
                .andExpect(status().isOk())
                .andExpect(view().name("recover"));

        mockMvc.perform(get("/knowledge"))
                .andExpect(status().isOk())
                .andExpect(view().name("knowledge"));

        mockMvc.perform(get("/advisory"))
                .andExpect(status().isOk())
                .andExpect(view().name("advisory"));

        mockMvc.perform(get("/assurance"))
                .andExpect(status().isOk())
                .andExpect(view().name("assurance"));

        mockMvc.perform(get("/compute"))
                .andExpect(status().isOk())
                .andExpect(view().name("compute"));

        mockMvc.perform(get("/models"))
                .andExpect(status().isOk())
                .andExpect(view().name("models"));

        mockMvc.perform(get("/orchestration"))
                .andExpect(status().isOk())
                .andExpect(view().name("orchestration"));

        mockMvc.perform(get("/settings/admin-suite"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin-suite"));
    }

    @Test
    void pollsLiveCloudInventoryAndGcpRuntimeTelemetryFromConnectionsPage() throws Exception {
        when(edgeClient.postJson(eq("/v1/inventory/poll"), any(), any(), any()))
                .thenReturn(java.util.Map.of("assetsNormalized", 5, "edges", List.of("e1", "e2")));
        when(edgeClient.postJson(eq("/v1/telemetry/poll/gcp"), any(), any(), any()))
                .thenReturn(java.util.Map.of("metricsCount", 2, "logsCount", 3));

        mockMvc.perform(post("/settings/connections/poll-cloud")
                        .param("provider", "GCP")
                        .param("scopeFilter", "*")
                        .param("includeRuntimeTelemetry", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/connections"))
                .andExpect(flash().attributeExists("flashSuccess"));

        when(edgeClient.postJson(eq("/v1/telemetry/poll/aws"), any(), any(), any()))
                .thenReturn(java.util.Map.of("metricsCount", 3, "logsCount", 2));

        mockMvc.perform(post("/settings/connections/poll-cloud")
                        .param("provider", "AWS")
                        .param("scopeFilter", "*")
                        .param("includeRuntimeTelemetry", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/connections"))
                .andExpect(flash().attributeExists("flashSuccess"));

        when(edgeClient.postJson(eq("/v1/recovery/live-mode"), any(), any(), any()))
                .thenReturn(java.util.Map.of("effectiveMode", "DRY_RUN_SIMULATED", "tenantToggleEnabled", true));

        mockMvc.perform(post("/recover/live-mode").param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/recover"))
                .andExpect(flash().attributeExists("flashSuccess"));

        when(edgeClient.postJson(eq("/v1/advisory/runs"), any(), any(), any()))
                .thenReturn(java.util.Map.of("producerRole", "Evidence assistant (GOOGLE_VERTEX)"));

        mockMvc.perform(post("/advisory/runs")
                        .param("caseCode", "AR-GCP-01")
                        .param("contextRef", "GR-1044")
                        .param("queryPrompt", "Summarize sandbox readiness"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/advisory"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }
}
