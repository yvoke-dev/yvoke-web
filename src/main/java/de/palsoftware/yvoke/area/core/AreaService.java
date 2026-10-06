package de.palsoftware.yvoke.area.core;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AreaService {

    private final AreaRepository areaRepository;

    public AreaService(AreaRepository areaRepository) {
        this.areaRepository = areaRepository;
    }

    public List<Area> listAreas() {
        return areaRepository.findAll();
    }

    public List<String> listAreaNames() {
        return areaRepository.findAllNames();
    }

    /**
     * Finds an area by name, ignoring case and surrounding spaces, and returns it with its STORED
     * name. Callers query with that stored name rather than the caller's spelling, so a lenient
     * match can never be followed by a strict query that finds nothing.
     */
    public Optional<Area> findArea(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim();
        return areaRepository.findAll().stream().filter(a -> a.name().equalsIgnoreCase(wanted))
            .findFirst();
    }

    /**
     * The stored name of an existing area. Everything belongs to exactly one area, so a missing or
     * unknown area is refused here with a message naming it, before the database's foreign key
     * would refuse it with a constraint name.
     */
    public String requireArea(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An area is required.");
        }
        return findArea(name).map(Area::name).orElseThrow(
            () -> new IllegalArgumentException("Area '" + name.trim() + "' does not exist."));
    }
}
