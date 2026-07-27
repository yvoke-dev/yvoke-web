package de.palsoftware.yvoke.kg.core.repository;

import de.palsoftware.yvoke.kg.core.model.KgCall;


import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public class KgCallMapper implements RowMapper<KgCall> {
    @Override
    public KgCall mapRow(ResultSet rs, int rowNum) throws SQLException {
        String name = rs.getString("name");
        String kind = rs.getString("kind");
        String description = rs.getString("description");
        String relationType = rs.getString("relation_type");
        return new KgCall(name, kind, description, relationType);
    }
}
