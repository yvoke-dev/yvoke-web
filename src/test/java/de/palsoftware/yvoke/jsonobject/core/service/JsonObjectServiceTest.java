package de.palsoftware.yvoke.jsonobject.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.jsonobject.core.model.JsonObject;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonObjectRepository;
import de.palsoftware.yvoke.jsonobject.core.repository.JsonSchemaRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JsonObjectServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void importResolvesExistingObjectsWithASingleBatchedProbe() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        when(extractor.extractSchema(anyList())).thenReturn(Map.of());
        when(schemaRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());

        UUID collectionId = UUID.randomUUID();
        UUID existingIdForA = UUID.randomUUID();
        // Only "a" already exists in the collection.
        when(repo.findIdsByJsonField(eq(collectionId), eq("id"), anyList()))
            .thenReturn(Map.of("a", existingIdForA));

        JsonObjectService service = new JsonObjectService(repo, schemaRepo, extractor);
        List<Map<String, Object>> objects =
            List.of(Map.of("id", "a"), Map.of("id", "b"), Map.of("id", "c"));

        service.importObjects(collectionId, "coll", objects, "src.json", List.of(), "id");

        // Exactly ONE probe query for the whole batch — never the per-object probe (PRF-02).
        ArgumentCaptor<List<String>> valuesCaptor = ArgumentCaptor.forClass(List.class);
        verify(repo, times(1)).findIdsByJsonField(eq(collectionId), eq("id"),
            valuesCaptor.capture());
        assertThat(valuesCaptor.getValue()).containsExactlyInAnyOrder("a", "b", "c");
        verify(repo, never()).findByJsonField(any(), anyString(), anyString());

        // "a" -> update (reusing its id); "b","c" -> insert.
        ArgumentCaptor<List<JsonObject>> insertCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<JsonObject>> updateCaptor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveBatch(insertCaptor.capture());
        verify(repo).updateBatch(updateCaptor.capture());
        assertThat(insertCaptor.getValue()).hasSize(2);
        assertThat(updateCaptor.getValue()).hasSize(1);
        assertThat(updateCaptor.getValue().get(0).id()).isEqualTo(existingIdForA);
    }

    @Test
    void importWithoutUniqueFieldInsertsEverythingAndSkipsTheProbe() {
        JsonObjectRepository repo = mock(JsonObjectRepository.class);
        JsonSchemaRepository schemaRepo = mock(JsonSchemaRepository.class);
        JsonSchemaExtractor extractor = mock(JsonSchemaExtractor.class);
        when(extractor.extractSchema(anyList())).thenReturn(Map.of());
        when(schemaRepo.findByCollectionId(any(), any())).thenReturn(Optional.empty());

        JsonObjectService service = new JsonObjectService(repo, schemaRepo, extractor);
        List<Map<String, Object>> objects = List.of(Map.of("id", "a"), Map.of("id", "b"));

        service.importObjects(UUID.randomUUID(), "coll", objects, "src.json", List.of(), null);

        verify(repo, never()).findIdsByJsonField(any(), anyString(), anyList());
        verify(repo).saveBatch(anyList());
    }
}
