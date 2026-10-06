package de.palsoftware.yvoke.area.core;

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
}
