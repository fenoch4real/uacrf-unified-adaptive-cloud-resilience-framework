package io.uacrf.edge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EdgeGatewayRouteTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void concealsInternalDelegationRouteAndReturnsBadGatewayWhenOwnerDown() throws Exception {
        // Internal delegation exchange must never be exposed by EDGE01
        mockMvc.perform(post("/v1/delegations/exchange"))
                .andExpect(status().isNotFound());

        // Public route returns 502 with no-store when downstream owner is unreachable
        mockMvc.perform(get("/v1/services?tenantId=11111111-1111-7111-8111-111111111111&environmentId=22222222-2222-7222-8222-222222222222"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        // Inventory poll & profile routes forward cleanly to S03 Discovery owner
        mockMvc.perform(get("/v1/inventory/profiles"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("DISCOVERY_UNAVAILABLE"));
    }
}
