package de.palsoftware.yvoke.area;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.palsoftware.yvoke.area.core.Area;
import de.palsoftware.yvoke.area.core.AreaRepository;
import de.palsoftware.yvoke.area.core.AreaService;
import java.util.Arrays;
import java.util.List;

/**
 * A real {@link AreaService} over a fixed list of areas, for unit tests of services that need one.
 */
public final class TestAreas {

    private TestAreas() {}

    /** An area service that knows the given areas (by name), and only those. */
    public static AreaService withAreas(String... names) {
        AreaRepository repository = mock(AreaRepository.class);
        List<Area> areas = Arrays.stream(names).map(TestAreas::area).toList();
        when(repository.findAll()).thenReturn(areas);
        when(repository.findAllNames()).thenReturn(Arrays.asList(names));
        return new AreaService(repository);
    }

    public static AreaService withAreaObjects(Area... areas) {
        AreaRepository repository = mock(AreaRepository.class);
        when(repository.findAll()).thenReturn(Arrays.asList(areas));
        when(repository.findAllNames()).thenReturn(Arrays.stream(areas).map(Area::name).toList());
        return new AreaService(repository);
    }

    public static Area area(String name) {
        return new Area(name, name, null, false, null, null, null, null, null);
    }

    public static Area areaWithDefaultPlaybook(String name, String defaultPlaybook) {
        return new Area(name, name, null, false, null, defaultPlaybook, null, null, null);
    }
}
