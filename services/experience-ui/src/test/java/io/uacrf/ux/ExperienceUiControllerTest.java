package io.uacrf.ux;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("overview"));

        mockMvc.perform(get("/journeys"))
                .andExpect(status().isOk())
                .andExpect(view().name("journeys"));

        mockMvc.perform(get("/agents"))
                .andExpect(status().isOk())
                .andExpect(view().name("agents"));
    }
}
