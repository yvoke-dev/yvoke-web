package de.palsoftware.yvoke.kg.core.repository;

import de.palsoftware.yvoke.shared.config.JdbcMappers;

import de.palsoftware.yvoke.kg.core.model.KgWalk;


import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;

public class KgWalkMapper implements RowMapper<KgWalk> {
    @Override
    public KgWalk mapRow(ResultSet rs, int rowNum) throws SQLException {
        int depth = rs.getInt("depth");
        List<String> pathList = JdbcMappers.arrayToStringList(rs, "path");
        return new KgWalk(depth, pathList);
    }
}
