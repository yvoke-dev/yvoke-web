package de.palsoftware.yvoke.area.core;

import org.springframework.dao.DataIntegrityViolationException;

import org.mockito.InOrder;

import java.util.Map;

import java.util.List;

import static org.mockito.Mockito.when;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.inOrder;

import static org.mockito.Mockito.doThrow;

import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.palsoftware.yvoke.area.TestAreas;
import org.junit.jupiter.api.Test;

class AreaServiceTest {

    private final AreaService service = TestAreas.withAreas("OIM", "PingID");

    /**
     * Validate leniently, query strictly: the caller's spelling is matched loosely, but what comes
     * back is the stored name, which is what every member row's foreign key must hold.
     */
    @Test
    void requireAreaIgnoresCaseAndSpacesAndReturnsTheStoredName() {
        assertThat(service.requireArea("  oim ")).isEqualTo("OIM");
        assertThat(service.requireArea("PINGID")).isEqualTo("PingID");
    }

    @Test
    void requireAreaRefusesAMissingArea() {
        assertThatThrownBy(() -> service.requireArea(null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("An area is required.");
        assertThatThrownBy(() -> service.requireArea("  "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("An area is required.");
    }

    @Test
    void requireAreaRefusesAnUnknownAreaByName() {
        assertThatThrownBy(() -> service.requireArea(" SAP "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Area 'SAP' does not exist.");
    }

    @Test
    void findAreaIsEmptyForAnUnknownOrBlankName() {
        assertThat(service.findArea("SAP")).isEmpty();
        assertThat(service.findArea("")).isEmpty();
        assertThat(service.findArea("oim")).get().extracting(Area::name).isEqualTo("OIM");
    }

    // --- members, defaults and the area page ---

    private static AreaRepository repoWith(List<Area> areas, Map<String, AreaMembers> members) {
        AreaRepository repository = mock(AreaRepository.class);
        when(repository.findAll()).thenReturn(areas);
        when(repository.findAllMembers()).thenReturn(members);
        return repository;
    }

    private static final AreaMembers OIM_MEMBERS = new AreaMembers(List.of("oim-chat"),
        List.of("OIM - Docs"), List.of("oim-full"), List.of("OIM"));

    /** A default the client cannot pick (not a member, or not pickable) is reported as none. */
    @Test
    void anAreasDefaultsCountOnlyWhenTheyAreMembers() {
        AreaService areas = new AreaService(repoWith(
            List.of(new Area("OIM", "OIM", null, false, "oim-chat", "oim-full", "OIM", null, null),
                new Area("Ping", "Ping", null, false, "oim-chat", "oim-full", "OIM", null, null)),
            Map.of("OIM", OIM_MEMBERS)));

        AreaWithMembers oim = areas.listAreasWithMembers().get(0);
        AreaWithMembers ping = areas.listAreasWithMembers().get(1);

        assertThat(oim.members()).isEqualTo(OIM_MEMBERS);
        assertThat(oim.defaultSystemPrompt()).isEqualTo("oim-chat");
        assertThat(oim.defaultPlaybook()).isEqualTo("oim-full");
        assertThat(oim.defaultProfile()).isEqualTo("OIM");
        assertThat(ping.members()).isEqualTo(AreaMembers.NONE);
        assertThat(ping.defaultSystemPrompt()).isNull();
        assertThat(ping.defaultPlaybook()).isNull();
        assertThat(ping.defaultProfile()).isNull();
        assertThat(areas.findAreaWithMembers(" oim ")).get().extracting(a -> a.area().name())
            .isEqualTo("OIM");
    }

    @Test
    void creatingAnAreaStoresItWithTheTitleDefaultingToTheName() {
        AreaRepository repository = repoWith(List.of(), Map.of());
        new AreaService(repository).saveArea(null, " PingID ", " ", " Ping docs ", true, "", null,
            " ");

        verify(repository)
            .upsert(new Area("PingID", "PingID", "Ping docs", true, null, null, null, null, null));
    }

    /**
     * Creating an area under a name that already exists must not quietly overwrite that area (the
     * ON CONFLICT pitfall): the page says so instead.
     */
    @Test
    void creatingAnAreaUnderAnExistingNameIsRefused() {
        AreaRepository repository = repoWith(List.of(TestAreas.area("OIM")), Map.of());

        assertThatThrownBy(() -> new AreaService(repository).saveArea(null, "oim", "x", null, false,
            null, null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Area 'OIM' already exists.");
        verify(repository, never()).upsert(any());
    }

    @Test
    void aBlankNameIsRefused() {
        AreaRepository repository = repoWith(List.of(), Map.of());
        assertThatThrownBy(() -> new AreaService(repository).saveArea(null, " ", "x", null, false,
            null, null, null)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("An area name is required.");
    }

    @Test
    void aDefaultMustBeAMemberOfTheArea() {
        AreaRepository repository =
            repoWith(List.of(TestAreas.area("OIM")), Map.of("OIM", OIM_MEMBERS));
        AreaService areas = new AreaService(repository);

        assertThatThrownBy(
            () -> areas.saveArea("OIM", "OIM", "OIM", null, false, null, "oim-orchestrator", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Playbook 'oim-orchestrator' is not a pickable playbook of area 'OIM'.");
        assertThatThrownBy(() -> areas.saveArea("OIM", "OIM", "OIM", null, false, "x", null, null))
            .hasMessage("System prompt 'x' is not a chat prompt of area 'OIM'.");
        assertThatThrownBy(() -> areas.saveArea("OIM", "OIM", "OIM", null, false, null, null, "x"))
            .hasMessage("Profile 'x' is not a profile of area 'OIM'.");

        areas.saveArea("OIM", "OIM", "Oracle", null, false, "oim-chat", "oim-full", "OIM");
        verify(repository).upsert(
            new Area("OIM", "Oracle", null, false, "oim-chat", "oim-full", "OIM", null, null));
    }

    /** A rename goes through the database's ON UPDATE CASCADE, so members follow it. */
    @Test
    void renamingAnAreaRenamesThenSaves() {
        AreaRepository repository =
            repoWith(List.of(TestAreas.area("OIM")), Map.of("OIM", OIM_MEMBERS));

        new AreaService(repository).saveArea("OIM", "Oracle IAM", "Oracle IAM", null, false,
            "oim-chat", null, null);

        InOrder order = inOrder(repository);
        order.verify(repository).rename("OIM", "Oracle IAM");
        order.verify(repository).upsert(
            new Area("Oracle IAM", "Oracle IAM", null, false, "oim-chat", null, null, null, null));
    }

    @Test
    void renamingOntoAnotherAreasNameIsRefused() {
        AreaRepository repository =
            repoWith(List.of(TestAreas.area("OIM"), TestAreas.area("PingID")), Map.of());

        assertThatThrownBy(() -> new AreaService(repository).saveArea("OIM", "pingid", "x", null,
            false, null, null, null)).hasMessage("Area 'PingID' already exists.");
        verify(repository, never()).rename(any(), any());
    }

    @Test
    void savingAnAreaThatNoLongerExistsIsRefused() {
        AreaRepository repository = repoWith(List.of(), Map.of());
        assertThatThrownBy(() -> new AreaService(repository).saveArea("Gone", "Gone", "x", null,
            false, null, null, null)).hasMessage("Area 'Gone' does not exist.");
    }

    @Test
    void anAreaWithMembersCannotBeDeleted() {
        AreaRepository repository = repoWith(List.of(TestAreas.area("OIM")), Map.of());
        doThrow(new DataIntegrityViolationException("fk")).when(repository).delete("OIM");

        assertThatThrownBy(() -> new AreaService(repository).deleteArea("oim"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Area 'OIM' still has system prompts, collections, playbooks or profiles. "
                + "Move or delete them first.");
    }

    @Test
    void deletingAnEmptyAreaDeletesItByItsStoredName() {
        AreaRepository repository = repoWith(List.of(TestAreas.area("PingID")), Map.of());
        new AreaService(repository).deleteArea(" pingid ");
        verify(repository).delete("PingID");
    }
}
