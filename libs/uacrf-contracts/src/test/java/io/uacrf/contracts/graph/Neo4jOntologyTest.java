package io.uacrf.contracts.graph;

import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Neo4jOntologyTest {

    @Test
    void enforcesExactCanonicalCounts() {
        assertEquals(58, Neo4jOntology.NodeLabel.values().length);
        assertEquals(55, Neo4jOntology.RelationshipType.values().length);
    }

    @Test
    void rejectsUndeclaredLabelsAndRelationships() {
        assertThrows(IllegalArgumentException.class, () -> Neo4jOntology.requireNodeLabel("MeasurementDefinition"));
        assertThrows(IllegalArgumentException.class, () -> Neo4jOntology.requireRelationshipType("MAY_TRIGGER"));
        assertEquals(Neo4jOntology.NodeLabel.Service, Neo4jOntology.requireNodeLabel("Service"));
        assertEquals(Neo4jOntology.RelationshipType.DEPENDS_ON, Neo4jOntology.requireRelationshipType("DEPENDS_ON"));
    }

    @Test
    void rejectsProhibitedSensitiveGraphProperties() {
        assertThrows(IllegalArgumentException.class,
                () -> Neo4jOntology.validatePropertyKeys(Set.of("tenant_id", "secret_value")));
        assertDoesNotThrow(
                () -> Neo4jOntology.validatePropertyKeys(Set.of("tenant_id", "canonical_id", "confidence")));
    }
}
